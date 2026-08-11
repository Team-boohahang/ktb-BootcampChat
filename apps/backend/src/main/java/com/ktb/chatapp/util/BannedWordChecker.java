package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.Assert;


//(기존 HashSet구현은 메시지 1건당 사전 전체를 순회하는 O(N*M),,) -> O(M)으로
public class BannedWordChecker {

    private static class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        boolean end;
    }

    private final Node root = new Node();

    public BannedWordChecker(Set<String> bannedWords) {
        List<String> words =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
        Assert.notEmpty(words, "Banned words set must not be empty");

        words.forEach(this::insert);
        buildFailureLinks();
    }

    private void insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), c -> new Node());
        }
        node.end = true;
    }

    private void buildFailureLinks() {
        Deque<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node current = queue.poll();
            for (Map.Entry<Character, Node> entry : current.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();
                Node failNode = current.fail;
                while (failNode != null && !failNode.children.containsKey(c)) {
                    failNode = failNode.fail;
                }
                child.fail = (failNode == null) ? root : failNode.children.get(c);
                if (child.fail.end) {
                    child.end = true;
                }
                queue.add(child);
            }
        }
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        Node node = root;
        for (int i = 0; i < normalizedMessage.length(); i++) {
            char c = normalizedMessage.charAt(i);
            while (node != root && !node.children.containsKey(c)) {
                node = node.fail;
            }
            node = node.children.getOrDefault(c, root);
            if (node.end) {
                return true;
            }
        }
        return false;
    }
}
