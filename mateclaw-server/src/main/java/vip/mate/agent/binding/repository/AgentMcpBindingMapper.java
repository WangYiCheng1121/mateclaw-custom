package vip.mate.agent.binding.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.agent.binding.model.AgentMcpBinding;

/**
 * Mapper for {@link AgentMcpBinding}.
 *
 * @author MateClaw Team
 */
@Mapper
public interface AgentMcpBindingMapper extends BaseMapper<AgentMcpBinding> {
}
