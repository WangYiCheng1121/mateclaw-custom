package vip.mate.skill.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 平台端技能目录树节点（对应 platform 的 TreeNodeVO）
 *
 * @author MateClaw Team
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformTreeNode {

    /** 节点ID */
    private String id;

    /** 节点名称 */
    private String name;

    /** 子节点 */
    private List<PlatformTreeNode> children;
}
