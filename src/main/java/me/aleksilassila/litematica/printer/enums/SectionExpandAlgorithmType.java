package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

/**
 * 扫描自动寻路的子区块扩展算法：
 * BFS 广度优先（默认）——逐层向外扩散，先扫完离玩家一圈的所有子区块；
 * DFS 深度优先——沿一个方向一路推进到底，碰壁（无可扫区块）再回头换方向，适合长条形建筑。
 * 两种模式的邻居生成都遵循「子区块扫描顺序」的轴序（每轴先 + 后 -）。
 */
public enum SectionExpandAlgorithmType implements ConfigOptionListEntry<SectionExpandAlgorithmType> {
    BFS(I18n.of("sectionExpandAlgorithm.bfs")),
    DFS(I18n.of("sectionExpandAlgorithm.dfs"));

    private final I18n i18n;

    SectionExpandAlgorithmType(I18n i18n) {
        this.i18n = i18n;
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
