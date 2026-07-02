package vip.mate.agent.binding;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.service.AgentBindingSkillRemovalListener;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.skill.event.SkillRemovedEvent;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentBindingSkillRemovalListener}.
 *
 * <ul>
 *   <li>Issue #127 — deleting a skill must drop orphan {@code mate_agent_skill} rows.</li>
 *   <li>The listener sets {@code hasSkillBinding = TRUE} on any agent whose
 *       last binding was just removed, preventing a fallback to the
 *       global-default "all skills" state.</li>
 * </ul>
 */
class AgentBindingSkillRemovalListenerTest {

    @Test
    @DisplayName("event triggers a delete and sets hasSkillBinding on zero-binding agents")
    void removalDropsBindingRowsAndFlagsAgent() {
        AgentSkillBindingMapper skillMapper = mock(AgentSkillBindingMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);

        AgentSkillBinding row = new AgentSkillBinding();
        row.setAgentId(1L);
        row.setSkillId(77L);
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        when(skillMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L); // zero remaining

        AgentBindingSkillRemovalListener listener =
                new AgentBindingSkillRemovalListener(skillMapper, agentMapper);
        listener.onSkillRemoved(new SkillRemovedEvent(77L, "pdf"));

        verify(skillMapper, times(1)).delete(any(LambdaQueryWrapper.class));
        // Agent now has 0 bindings → must set hasSkillBinding = TRUE
        verify(agentMapper, times(1)).update(any(), argThat(w ->
                w instanceof LambdaUpdateWrapper<?>));
    }

    @Test
    @DisplayName("agent still has other bindings → hasSkillBinding NOT updated")
    void agentWithRemainingBindingsNotFlagged() {
        AgentSkillBindingMapper skillMapper = mock(AgentSkillBindingMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);

        AgentSkillBinding row = new AgentSkillBinding();
        row.setAgentId(1L);
        row.setSkillId(77L);
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(row));
        when(skillMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(1);
        when(skillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L); // still has bindings

        AgentBindingSkillRemovalListener listener =
                new AgentBindingSkillRemovalListener(skillMapper, agentMapper);
        listener.onSkillRemoved(new SkillRemovedEvent(77L, "pdf"));

        verify(skillMapper, times(1)).delete(any(LambdaQueryWrapper.class));
        verify(agentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("null event or null skillId is a no-op — defensive guard")
    void nullEventDoesNothing() {
        AgentSkillBindingMapper skillMapper = mock(AgentSkillBindingMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentBindingSkillRemovalListener listener =
                new AgentBindingSkillRemovalListener(skillMapper, agentMapper);

        listener.onSkillRemoved(null);
        listener.onSkillRemoved(new SkillRemovedEvent(null, "dangling"));

        verify(skillMapper, never()).delete(any());
        verify(agentMapper, never()).update(any(), any());
    }
}
