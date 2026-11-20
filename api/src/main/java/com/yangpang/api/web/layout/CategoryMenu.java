package com.yangpang.api.web.layout;

import com.yangpang.domain.catalog.Category;
import com.yangpang.domain.catalog.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 카테고리 트리 (헤더 메가메뉴, 브레드크럼, 하위 카테고리 조회).
 * MVP에서는 카테고리가 바뀌지 않으므로 처음 한 번 읽어 메모리에 둔다. 관리자가 수정하면 invalidate() 호출.
 */
@Component
@RequiredArgsConstructor
public class CategoryMenu {

    public record Node(Long id, Long parentId, String name, int depth, String icon, List<Node> children) {
        public boolean isLeaf() {
            return children.isEmpty();
        }
    }

    private record Tree(List<Node> roots, Map<Long, Node> byId) {
    }

    private final CategoryRepository categoryRepository;
    private volatile Tree tree;

    public List<Node> roots() {
        return tree().roots();
    }

    public Optional<Node> find(Long id) {
        return Optional.ofNullable(id == null ? null : tree().byId().get(id));
    }

    /** 대분류부터 자기 자신까지의 경로 */
    public List<Node> path(Long id) {
        LinkedList<Node> path = new LinkedList<>();
        Node node = tree().byId().get(id);
        while (node != null) {
            path.addFirst(node);
            node = node.parentId() == null ? null : tree().byId().get(node.parentId());
        }
        return path;
    }

    /** 자기 자신 + 모든 하위 카테고리 id */
    public Set<Long> selfAndDescendantIds(Long id) {
        Set<Long> ids = new HashSet<>();
        find(id).ifPresent(n -> collect(n, ids));
        return ids;
    }

    private void collect(Node node, Set<Long> ids) {
        ids.add(node.id());
        node.children().forEach(child -> collect(child, ids));
    }

    public void invalidate() {
        tree = null;
    }

    private Tree tree() {
        Tree t = tree;
        if (t == null) {
            synchronized (this) {
                if (tree == null) tree = load();
                t = tree;
            }
        }
        return t;
    }

    private Tree load() {
        Map<Long, Node> byId = new LinkedHashMap<>();
        List<Node> roots = new ArrayList<>();
        for (Category c : categoryRepository.findAllByOrderByDepthAscSortOrderAscIdAsc()) {
            Node node = new Node(c.getId(), c.getParentId(), c.getName(), c.getDepth(), c.getIcon(), new ArrayList<>());
            byId.put(c.getId(), node);
            if (c.getParentId() == null) {
                roots.add(node);
            } else {
                Node parent = byId.get(c.getParentId());
                if (parent != null) parent.children().add(node);
            }
        }
        return new Tree(List.copyOf(roots), Map.copyOf(byId));
    }
}
