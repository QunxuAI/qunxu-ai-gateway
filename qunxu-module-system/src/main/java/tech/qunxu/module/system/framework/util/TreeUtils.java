package tech.qunxu.module.system.framework.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 通用树形结构构建工具。
 * <p>
 * 将平铺列表构建为树形结构：parentId 为 0（或父节点不在列表中）的节点作为根节点。
 *
 * <pre>{@code
 * List<MenuRespVO> tree = TreeUtils.build(list,
 *         MenuRespVO::getId, MenuRespVO::getParentId,
 *         MenuRespVO::getChildren, MenuRespVO::setChildren);
 * }</pre>
 */
public final class TreeUtils {

    private TreeUtils() {
    }

    /**
     * 构建树
     *
     * @param nodes       平铺节点列表
     * @param getId       获取节点编号
     * @param getParentId 获取父节点编号
     * @param getChildren 获取子节点列表
     * @param setChildren 设置子节点列表
     * @return 根节点列表
     */
    public static <T> List<T> build(List<T> nodes,
                                    Function<T, Long> getId,
                                    Function<T, Long> getParentId,
                                    Function<T, List<T>> getChildren,
                                    BiConsumer<T, List<T>> setChildren) {
        List<T> roots = new ArrayList<>();
        // 收集节点编号，用于判断父节点是否在列表中
        Map<Long, T> nodeMap = new HashMap<>();
        for (T node : nodes) {
            nodeMap.put(getId.apply(node), node);
        }
        // 挂载子节点
        for (T node : nodes) {
            Long parentId = getParentId.apply(node);
            if (parentId != null && parentId != 0L && nodeMap.containsKey(parentId)) {
                // 父节点在列表中，作为子节点挂载
                List<T> children = getChildren.apply(nodeMap.get(parentId));
                if (children == null) {
                    children = new ArrayList<>();
                    setChildren.accept(nodeMap.get(parentId), children);
                }
                children.add(node);
            } else {
                // 父节点为 0 或不在列表中，作为根节点
                roots.add(node);
            }
        }
        return roots;
    }

}
