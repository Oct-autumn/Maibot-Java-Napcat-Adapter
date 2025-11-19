package org.maibot.mods.ncada.evtbuffer;

import org.maibot.mods.ncada.msgevt.MessageEvent;
import org.maibot.sdk.SNoGenerator;
import org.maibot.sdk.TaskExecuteService;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.ioc.Component;
import org.maibot.sdk.ioc.DestroyableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 消息缓冲队列
 * <p>
 * 用于缓冲要推送至Core的消息，确保消息按序发送。
 * <p>
 * 实现上通过类似TCP保序传输的方案，给每条消息一个序列号，按序号顺序发送。
 * 若后到的消息处理完时发现前序消息还未处理完，则将该消息放入缓冲区，等待前序消息处理完毕后再发送。
 * 超时机制：若前序消息已到达超过3秒仍未处理完，则跳过它，先检查后续消息是否可以发送。超时消息则等处理完毕后再发送。
 */
@Component
public class BarrierOrderedEvtQueue implements DestroyableComponent {
    private static final Logger log        = LoggerFactory.getLogger(BarrierOrderedEvtQueue.class);
    private static final long   TIMEOUT_MS = 3000;

    private final Queue<SNoGenerator.SerialNo>               processQueue  = new ConcurrentLinkedQueue<>();
    private final Map<SNoGenerator.SerialNo, DelayQueueItem> processingMap = new HashMap<>();

    private final TaskExecuteService executorService;

    private final ReentrantLock notifyLock = new ReentrantLock();
    private final Condition     notEmpty   = notifyLock.newCondition();

    private volatile boolean isRunning = false;

    @AutoInject
    private BarrierOrderedEvtQueue(
      TaskExecuteService executorService
    ) {
        this.executorService = executorService;
    }

    /**
     * 记录开始反序列化消息
     *
     * @param msgSNo      消息序列号
     * @param receiveTime 接收时间戳
     */
    public void startDeserializing(SNoGenerator.SerialNo msgSNo, long receiveTime) {
        processQueue.offer(msgSNo);
        processingMap.put(msgSNo, new DelayQueueItem(receiveTime));
    }

    /**
     * 完成消息反序列化
     *
     * @param msg 消息对象，其中的序列号为记录开始反序列化时传入的序列号
     */
    public void finishDeserializing(MessageEvent msg) {
        var now = System.currentTimeMillis();
        SNoGenerator.SerialNo msgSNo = msg.sequence();
        if (processQueue.peek() == msgSNo) {
            // 无处理中的前序消息，直接发送
            processQueue.poll();
            var item = processingMap.remove(msgSNo);
            sendBufferedMessage(msg, now - item.receiveTime, 0);
        } else {
            // 有处理中的前序消息，放入缓冲区
            var item = processingMap.get(msgSNo);
            if (item != null) {
                item.finish(msg, now - item.receiveTime);

                try {
                    notifyLock.lock();
                    notEmpty.signalAll();
                } finally {
                    notifyLock.unlock();
                }
            } else {
                // 不应该发生
                reportBug(msgSNo);
            }
        }
    }

    /**
     * 反序列化失败
     *
     * @param msgSNo 消息序列号
     */
    public void failDeserializing(SNoGenerator.SerialNo msgSNo) {
        var now = System.currentTimeMillis();
        if (processQueue.peek() == msgSNo) {
            // 无处理中的前序消息，直接移除
            processQueue.poll();
            processingMap.remove(msgSNo);
        } else {
            // 有处理中的前序消息，标记为已完成但消息为null
            var item = processingMap.get(msgSNo);
            if (item != null) {
                item.fail(now - item.receiveTime);
                try {
                    notifyLock.lock();
                    notEmpty.signalAll();
                } finally {
                    notifyLock.unlock();
                }
            } else {
                // 不应该发生
                reportBug(msgSNo);
            }
        }
    }

    public void startBuffering() {
        if (isRunning) {
            return;
        }
        log.debug("已启动消息缓冲队列");
        isRunning = true;
        executorService.submit(
          () -> {
              while (isRunning) {
                  try {
                      notifyLock.lock();
                      while (processQueue.isEmpty()) {
                          notEmpty.await(); // 等待有新的消息到来
                      }
                  } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                  } finally {
                      notifyLock.unlock();
                  }

                  // 检查缓冲区中的消息
                  var now = System.currentTimeMillis();
                  // 从队头开始：
                  // - 若消息已处理完毕则发送
                  // - 若消息未处理完毕但已超时则跳过
                  // - 否则停止检查
                  var queueIterator = processQueue.iterator();
                  while (queueIterator.hasNext()) {
                      var nowSNo = queueIterator.next();
                      var item = processingMap.get(nowSNo);
                      if (item == null) {
                          // 不应该发生
                          reportBug(nowSNo);
                      } else {
                          if (item.processDelay >= 0) {
                              // 消息已处理完毕，发送
                              queueIterator.remove();
                              processingMap.remove(nowSNo);

                              if (item.msg == null) {
                                  // 反序列化失败的消息，跳过发送
                                  log.debug("跳过发送反序列化失败的消息（SNo.{}）", nowSNo.toHexString());
                              } else {
                                  sendBufferedMessage(
                                    item.msg,
                                    item.processDelay,
                                    now - item.receiveTime - item.processDelay
                                  );
                              }
                          } else if (now - item.receiveTime < TIMEOUT_MS) {
                              // 消息未处理完毕且未超时，停止检查
                              break;
                          }
                          // 消息未处理完毕但已超时，跳过且不移除，后续遍历时若其处理完毕再发送
                      }
                  }

                  try {
                      notifyLock.lock();
                      var ignore = notEmpty.await(100, TimeUnit.MILLISECONDS); // 定期检查
                  } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                  } finally {
                      notifyLock.unlock();
                  }
              }
          }, false
        );
    }

    private void reportBug(SNoGenerator.SerialNo sNo) {
        log.warn(
          "无法找到消息（SNo.{}）的缓冲项，可能已被移除？这是一个Bug，如有可能请联系开发者",
          sNo.toHexString()
        );
    }

    public void stopBuffering() {
        isRunning = false;
        try {
            notifyLock.lock();
            notEmpty.signalAll(); // 唤醒等待线程以便退出
        } finally {
            notifyLock.unlock();
        }
        log.debug("已停止消息缓冲队列");
    }


    private void sendBufferedMessage(MessageEvent msg, long processDelay, long queueDelay) {
        log.debug(
          "推送消息（SNo.{}）至core，处理延迟：{}ms，排队延迟：{}ms",
          msg.sequence().toHexString(),
          processDelay,
          queueDelay
        );
        // TODO: 推送消息到Core
    }

    @Override
    public void preDestroy() {
        stopBuffering();
    }

    private static class DelayQueueItem {
        final    long         receiveTime;
        volatile MessageEvent msg          = null;
        volatile long         processDelay = -1;

        public DelayQueueItem(long receiveTime) {
            this.receiveTime = receiveTime;
        }

        public void finish(MessageEvent msg, long processDelay) {
            this.msg = msg;
            this.processDelay = processDelay;
        }

        public void fail(long processDelay) {
            this.msg = null;
            this.processDelay = processDelay;
        }
    }
}
