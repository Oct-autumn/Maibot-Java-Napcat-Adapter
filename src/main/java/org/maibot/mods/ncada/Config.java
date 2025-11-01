package org.maibot.mods.ncada;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.maibot.sdk.config.Configuration;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.Set;

@Configuration
public record Config(
  @JsonProperty(value = "qq_account", required = true) String qqAccount,
  @JsonProperty(value = "nickname", required = true) String nickname,
  @JsonProperty(value = "group_filter", required = true) GroupFilter groupFilter,
  @JsonProperty(value = "friend_filter", required = true) FriendFilter friendFilter,
  @JsonProperty(value = "banned_users", required = true) BannedUsers banned,
  @JsonProperty(value = "enable_poke", defaultValue = "false") Boolean enablePoke
) {
    public enum FilterType {
        WHITELIST("whitelist"),
        BLACKLIST("blacklist");

        private final String type;

        FilterType(String type) {
            this.type = type;
        }

        @JsonCreator
        public static FilterType fromString(String type) {
            var lowerCased = type.toLowerCase();
            return switch (lowerCased) {
                case "whitelist" -> WHITELIST;
                case "blacklist" -> BLACKLIST;
                default -> throw new IllegalArgumentException("Unknown filter type: " + type);
            };
        }

        @JsonSerialize
        public String getType() {
            return type;
        }
    }

    public record GroupFilter(
      @JsonProperty(value = "filter_type", required = true) FilterType filterType,
      @JsonProperty(value = "group_set", required = true) Set<String> groupSet
    ) {
    }

    public record FriendFilter(
      @JsonProperty(value = "filter_type", required = true) FilterType filterType,
      @JsonProperty(value = "friend_set", required = true) Set<String> friendSet
    ) {
    }

    public record BannedUsers(
      @JsonProperty(value = "banned_user_set", required = true) Set<String> bannedUserSet
      // 尚未支持
      //@JsonProperty(value = "ban_qq_bot", defaultValue = "false") Boolean banQQBot
    ) {
    }
}
