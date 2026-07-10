package vip.mate.agent.binding.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.agent.binding.model.AgentKnowledgeBaseBinding;

/**
 * Mapper for {@link AgentKnowledgeBaseBinding}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface AgentKnowledgeBaseBindingMapper extends BaseMapper<AgentKnowledgeBaseBinding> {
}
