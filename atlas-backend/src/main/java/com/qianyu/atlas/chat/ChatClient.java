package com.qianyu.atlas.chat;

import java.util.List;
import java.util.function.Consumer;

public interface ChatClient {
    String complete(List<Message> messages);

    default void completeStream(List<Message> messages, Consumer<String> onDelta) {
        onDelta.accept(complete(messages));
    }

    String modelName();

    Long providerId();

    record Message(String role, String content) {
    }
}
