package vip.mate.skill.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mate.skill.model.SkillEntity;

/**
 * 技能 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {

    /**
     * Physical delete by id. Same as {@link BaseMapper#deleteById};
     * kept for backward-compatible callers that expect this method name.
     */
    @Delete("DELETE FROM mate_skill WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);
}
