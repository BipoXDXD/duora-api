package bipo.tech.duoraapi.matching.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Emparelhamento máximo num grafo qualquer pelo algoritmo de Edmonds (blossom), em O(n³). As pessoas são os
 * vértices 0..n-1, e há aresta entre duas quando o par é permitido. Quem fica de fora não segue nenhuma
 * prioridade: isso é papel de {@link PriorityMatching}.
 */
final class MaximumMatching {

    static final int UNMATCHED = -1;

    private static final int NONE = -1;

    /** Se dois vértices podem formar par. */
    @FunctionalInterface
    interface Compatibility {
        boolean allows(int one, int other);
    }

    private final List<int[]> neighbours;
    private final int[] match;
    private final int[] parent;
    private final int[] base;
    private final boolean[] inTree;
    private final boolean[] inBlossom;
    private final int[] queue;
    private int queueHead;
    private int queueTail;

    private MaximumMatching(int vertices, Compatibility compatibility) {
        neighbours = new ArrayList<>(vertices);
        for (int vertex = 0; vertex < vertices; vertex++) {
            neighbours.add(neighboursOf(vertex, vertices, compatibility));
        }
        match = new int[vertices];
        Arrays.fill(match, UNMATCHED);
        parent = new int[vertices];
        base = new int[vertices];
        inTree = new boolean[vertices];
        inBlossom = new boolean[vertices];
        queue = new int[vertices];
    }

    /** O par de cada vértice no emparelhamento máximo, ou {@link #UNMATCHED}. */
    static int[] partners(int vertices, Compatibility compatibility) {
        MaximumMatching matching = new MaximumMatching(vertices, compatibility);
        for (int root = 0; root < vertices; root++) {
            if (matching.match[root] == UNMATCHED) {
                matching.augmentFrom(root);
            }
        }
        return matching.match.clone();
    }

    private static int[] neighboursOf(int vertex, int vertices, Compatibility compatibility) {
        int[] found = new int[vertices];
        int count = 0;
        for (int other = 0; other < vertices; other++) {
            if (other != vertex && compatibility.allows(vertex, other)) {
                found[count++] = other;
            }
        }
        return Arrays.copyOf(found, count);
    }

    /** Procura um caminho de aumento a partir da raiz livre e, se achar, inverte as arestas dele. */
    private void augmentFrom(int root) {
        int end = findAugmentingPathEnd(root);
        while (end != NONE) {
            int previous = parent[end];
            int next = match[previous];
            match[end] = previous;
            match[previous] = end;
            end = next;
        }
    }

    private int findAugmentingPathEnd(int root) {
        Arrays.fill(parent, NONE);
        Arrays.fill(inTree, false);
        for (int vertex = 0; vertex < base.length; vertex++) {
            base[vertex] = vertex;
        }
        inTree[root] = true;
        queueHead = 0;
        queueTail = 0;
        queue[queueTail++] = root;
        while (queueHead < queueTail) {
            int vertex = queue[queueHead++];
            for (int neighbour : neighbours.get(vertex)) {
                if (base[vertex] == base[neighbour] || match[vertex] == neighbour) {
                    continue;
                }
                if (closesOddCycle(root, neighbour)) {
                    contractBlossom(vertex, neighbour);
                } else if (parent[neighbour] == NONE) {
                    parent[neighbour] = vertex;
                    if (match[neighbour] == UNMATCHED) {
                        return neighbour;
                    }
                    enqueue(match[neighbour]);
                }
            }
        }
        return NONE;
    }

    /** O vizinho está num nível par da árvore: a aresta fecha um ciclo ímpar. */
    private boolean closesOddCycle(int root, int neighbour) {
        return neighbour == root || match[neighbour] != UNMATCHED && parent[match[neighbour]] != NONE;
    }

    private void contractBlossom(int vertex, int neighbour) {
        int blossomBase = lowestCommonAncestor(vertex, neighbour);
        Arrays.fill(inBlossom, false);
        markPath(vertex, blossomBase, neighbour);
        markPath(neighbour, blossomBase, vertex);
        for (int other = 0; other < base.length; other++) {
            if (inBlossom[base[other]]) {
                base[other] = blossomBase;
                if (!inTree[other]) {
                    enqueue(other);
                }
            }
        }
    }

    private void enqueue(int vertex) {
        inTree[vertex] = true;
        queue[queueTail++] = vertex;
    }

    private int lowestCommonAncestor(int one, int other) {
        boolean[] onPath = new boolean[base.length];
        int current = one;
        while (true) {
            current = base[current];
            onPath[current] = true;
            if (match[current] == UNMATCHED) {
                break;
            }
            current = parent[match[current]];
        }
        current = other;
        while (true) {
            current = base[current];
            if (onPath[current]) {
                return current;
            }
            current = parent[match[current]];
        }
    }

    private void markPath(int vertex, int blossomBase, int child) {
        int current = vertex;
        int currentChild = child;
        while (base[current] != blossomBase) {
            inBlossom[base[current]] = true;
            inBlossom[base[match[current]]] = true;
            parent[current] = currentChild;
            currentChild = match[current];
            current = parent[match[current]];
        }
    }

}
