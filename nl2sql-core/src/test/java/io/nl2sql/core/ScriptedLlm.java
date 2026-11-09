package io.nl2sql.core;

import io.nl2sql.core.llm.LlmProvider;
import io.nl2sql.core.llm.LlmResponse;
import io.nl2sql.core.llm.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Returns queued responses in order and records every prompt it saw. */
public class ScriptedLlm implements LlmProvider {
    public final Deque<String> responses = new ArrayDeque<>();
    public final List<List<Message>> calls = new ArrayList<>();

    public ScriptedLlm queue(String... texts) {
        for (String t : texts) responses.add(t);
        return this;
    }

    /** {"sql": ..., "explanation": "", "clarification": null} */
    public ScriptedLlm sql(String... sqls) {
        for (String s : sqls) responses.add("{\"sql\": " + quote(s) + ", \"explanation\": \"e\", \"clarification\": null}");
        return this;
    }

    @Override
    public LlmResponse complete(List<Message> messages) {
        calls.add(new ArrayList<>(messages));
        if (responses.isEmpty()) throw new AssertionError("ScriptedLlm has no response left");
        return new LlmResponse(responses.poll(), "scripted");
    }

    public String lastUserPrompt() {
        List<Message> last = calls.get(calls.size() - 1);
        return last.get(last.size() - 1).content();
    }

    public static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
