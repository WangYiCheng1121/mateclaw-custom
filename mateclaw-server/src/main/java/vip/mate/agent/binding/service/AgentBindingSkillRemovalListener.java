package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.skill.event.SkillRemovedEvent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drops {@code mate_agent_skill} rows that pointed at a now-removed skill.
 *
 * <p>Without this listener, deleting a skill from the skill management page
 * leaves orphan binding rows behind:
 * <ul>
 *   <li>the agent edit modal still shows a non-zero badge from
 *       {@code GET /agents/{id}/skills},</li>
 *   <li>the picker list (sourced from {@code /skills} enabled set) no longer
 *       contains a checkbox for that id so the user can't uncheck it, and</li>
 *   <li>a subsequent {@code PUT /agents/{id}/skills} payload that still
 *       carries the orphan id is rejected by
 *       {@code AgentBindingService.setSkillBindings} with
 *       {@code err.skill.not_found}, leaving the user with no way to clear
 *       the stale binding.</li>
 * </ul>
 *
 * <p>The event is dispatched synchronously from {@code SkillService} after
 * the {@code mate_skill} row deletion, so the cleanup is part of the same
 * request and observable in the very next list call.
 *
 * <h3>Zero-binding protection</h3>
 * <p>When the last bound skill is removed by platform-side deletion, this
 * listener sets {@code mate_agent.has_skill_binding = TRUE} on the affected
 * agent.  This prevents {@code getBoundSkillIds} from returning
 * {@code null} (which means "all skills"), keeping the agent locked to an
 * empty allowlist (no skills) until the user deliberately re-configures
 * bindings via {@code PUT /agents/{id}/skills}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBindingSkillRemovalListener {

    private final AgentSkillBindingMapper skillBindingMapper;
    private final AgentMapper agentMapper;

    @EventListener
    public void onSkillRemoved(SkillRemovedEvent event) {
        if (event == null || event.skillId() == null) {
            return;
        }

        // 1. Capture affected agent ids BEFORE the delete.
        List<AgentSkillBinding> existing = skillBindingMapper.selectList(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getSkillId, event.skillId()));
        Set<Long> affectedAgentIds = existing.stream()
                .map(AgentSkillBinding::getAgentId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        // 2. Delete the now-orphan binding rows (hard-delete).
        int dropped = skillBindingMapper.delete(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getSkillId, event.skillId()));
        if (dropped > 0) {
            log.info("Cleaned {} agent-skill binding row(s) for removed skill {} (id={})",
                    dropped, event.skillName(), event.skillId());
        }

        // 3. For each affected agent that now has zero remaining bindings,
        //    set hasSkillBinding = TRUE so getBoundSkillIds returns empty-set
        //    ("no skills") rather than null ("all skills").
        for (Long agentId : affectedAgentIds) {
            Long remaining = skillBindingMapper.selectCount(
                    new LambdaQueryWrapper<AgentSkillBinding>()
                            .eq(AgentSkillBinding::getAgentId, agentId));
            if (remaining == 0) {
                agentMapper.update(null,
                        new LambdaUpdateWrapper<AgentEntity>()
                                .set(AgentEntity::getHasSkillBinding, true)
                                .eq(AgentEntity::getId, agentId));
                log.info("Agent {} now has zero skill bindings after removal of {}; "
                        + "hasSkillBinding set to TRUE", agentId, event.skillName());
            }
        }
    }
}
