package vip.mate.agent.binding.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent ↔ Platform Knowledge Base binding.
 *
 * <p>Client-side ref stub only — stores the platform knowledge base
 * reference ID and its display name for UI rendering. Actual knowledge
 * base traffic is routed through the platform LLM proxy.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_agent_knowledge_base")
public class AgentKnowledgeBaseBinding {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    /** Platform knowledge base reference ID */
    private String kbRefId;

    /** Platform knowledge base display name (cached for UI) */
    private String kbName;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
