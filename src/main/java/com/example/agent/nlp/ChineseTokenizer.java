package com.example.agent.nlp;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import java.util.ArrayList;
import java.util.List;

/**
 * 中文分词器 — 基于 jieba 字典 + HMM。
 * 替换原有的 bigram 分词，消除"器学""然语"等伪词噪音。
 */
public class ChineseTokenizer {

    private static volatile JiebaSegmenter segmenter;

    private static JiebaSegmenter getSegmenter() {
        if (segmenter == null) {
            synchronized (ChineseTokenizer.class) {
                if (segmenter == null) {
                    segmenter = new JiebaSegmenter();
                }
            }
        }
        return segmenter;
    }

    /** 对中文文本分词，返回词列表（滤除空白和单字标点） */
    public static List<String> segment(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        List<SegToken> tokens = getSegmenter().process(text, JiebaSegmenter.SegMode.SEARCH);
        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (word.isEmpty()) continue;
            // 过滤纯标点/空格 token
            if (word.chars().allMatch(c -> !Character.isLetterOrDigit(c)
                    && Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN)) {
                continue;
            }
            result.add(word.toLowerCase());
        }
        return result;
    }

    /** 分词并统计词频 */
    public static java.util.Map<String, Integer> segmentWithFreq(String text) {
        java.util.Map<String, Integer> tf = new java.util.LinkedHashMap<>();
        for (String word : segment(text)) {
            tf.merge(word, 1, Integer::sum);
        }
        return tf;
    }
}
