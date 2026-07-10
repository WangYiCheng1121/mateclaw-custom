package vip.mate.agent.binding.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent ↔ Platform MCP binding.
 *
 * <p>Client-side ref stub only — stores the platform MCP reference ID
 * and its display name for UI rendering. Actual MCP traffic is routed
 * through the platform LLM proxy.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_agent_mcp")
public class AgentMcpBinding {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long agentId;

    /** Platform MCP reference ID */
    private Integer mcpRefId;

    /** Platform MCP display name (cached for UI) */
    private String mcpName;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
