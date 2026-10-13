package tech.qunxu.module.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 敏感词实体，对应表 {@code sensitive_word}。用于安全围栏的敏感词 DFA/Trie 匹配。
 */
@TableName("sensitive_word")
public class SensitiveWordEntity extends BaseConfigEntity {

    /** 敏感词文本。 */
    private String word;
    /** 分类: 涉政/色情/暴恐/广告/辱骂等。 */
    private String category;
    /** 级别: LOW / MEDIUM / HIGH。 */
    private String level;
    /** 命中动作: BLOCK / MASK / WARN。 */
    private String action;
    /** 1 启用 0 停用。 */
    private Integer enabled;

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
}
