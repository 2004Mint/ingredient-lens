package com.cosme.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「研究出来的成分要不要新建一条」的判定。
 *
 * <p>线上真出过的事故：彩妆里的 CI 77891 研究成功，但模型给的标准名是「Titanium Dioxide」，
 * 而库里早就以别名形式把它收在「二氧化钛」下。只查标准名 → 判成新成分 → 唯一性校验报「重名」
 * → 任务失败三次，用户点开成分详情永远只有一句「CI 77891。」。</p>
 *
 * <p>这里锁住两件事：编号/别名这类同义写法必须并成别名；名字像但其实是两种物质的，
 * 一条都不能并（「透明质酸」不等于「透明质酸钠」）。</p>
 */
class ResearchCommitServiceTest {

    @Test
    @DisplayName("并别名：模型自己把查询词列进了别名")
    void mergesWhenModelListsTheQuery() {
        assertTrue(ResearchCommitService.canMergeAlias(
                "CI 77891", Arrays.asList("Titanium Dioxide", "CI 77891"), "二氧化钛"));
        // 查询词就是标准名：等于没做事，直接返回已有条目
        assertTrue(ResearchCommitService.canMergeAlias("香精", Collections.emptyList(), "香精"));
        // 括号变体：(日用)香精 → 香精
        assertTrue(ResearchCommitService.canMergeAlias("(日用)香精", Collections.emptyList(), "香精"));
        // 没有查询词（rare）：按已有条目收口
        assertTrue(ResearchCommitService.canMergeAlias("", Collections.emptyList(), "香精"));
    }

    @Test
    @DisplayName("并别名：着色剂编号按编号本身判断")
    void mergesColorIndexByItsOwnCode() {
        assertTrue(ResearchCommitService.canMergeAlias(
                "CI 77891", Collections.emptyList(), "二氧化钛"));
        // 大小写与空格都算同一个编号
        assertTrue(ResearchCommitService.canMergeAlias(
                "ci 77891", Collections.emptyList(), "二氧化钛"));
    }

    @Test
    @DisplayName("拒绝：编号差一位就是另一种颜料")
    void refusesDifferentColorIndex() {
        // CI 77491 是氧化铁红，CI 77492 是氧化铁黄。模型把红写成了黄，
        // 就算它把 77491 列进了自己的别名，也不能并进 77492 那条。
        assertFalse(ResearchCommitService.canMergeAlias(
                "CI 77491", Arrays.asList("CI 77491", "CI 77492"), "CI 77492（氧化铁黄）"));
        // :1 后缀是另一种色淀，同样不能并
        assertFalse(ResearchCommitService.canMergeAlias(
                "CI 15850:1", Collections.emptyList(), "CI 15850"));
    }

    @Test
    @DisplayName("拒绝：名字相近但是两种物质，宁可报冲突也不并")
    void refusesLookalikeNames() {
        // 酸和盐是两条记录，同名化成别名会把风险等级带错
        assertFalse(ResearchCommitService.canMergeAlias(
                "透明质酸", Collections.emptyList(), "透明质酸钠"));
        assertFalse(ResearchCommitService.canMergeAlias(
                "羟苯丙酯", Collections.emptyList(), "羟苯甲酯"));
        // 标准名为空时不做任何合并
        assertFalse(ResearchCommitService.canMergeAlias("烟酰胺", Collections.emptyList(), ""));
    }
}
