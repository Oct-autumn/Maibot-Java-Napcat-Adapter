package org.maibot.mods.ncada;

import org.maibot.sdk.exceptions.FatalError;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class QQFace {
    private final Map<String, String> mapping;

    public QQFace() {
        try (var stream = QQFace.class.getResourceAsStream("/org/maibot/mods/ncada/qq_face_map.json")) {
            if (stream == null) {
                throw new RuntimeException("QQ face mapping resource not found");
            }
            var mapping = new ObjectMapper().readTree(stream);
            if (mapping instanceof ObjectNode objectNode) {
                this.mapping = new HashMap<>();
                objectNode.forEachEntry((key, value) -> this.mapping.put(key, value.asString()));
            } else {
                throw new FatalError("Invalid QQ face mapping format");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load QQ face mapping", e);
        }
    }

    public String getMapping(String code) {
        return mapping.get(code);
    }
}
