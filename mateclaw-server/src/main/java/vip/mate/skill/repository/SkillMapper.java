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
     * RFC-090 §14.5 — physical delete bypassing the {@code deleted}
     * logical-delete flag. Used by the admin "hard delete" path
     * ({@code DELETE /skills/{id}}); the user-facing "uninstall" path
     * still goes through {@link BaseMapper#deleteById} so the row can
     * be recovered by re-installing the same skill name.
     */
    @Delete("DELETE FROM mate_skill WHERE id = #{id}")
    int hardDeleteById(@Param("id") Long id);


    /**
     * 物理删除指定名称的软删除残留记录（绕过 @TableLogic）
     * <p>
     * 用于平台同步场景：技能被平台取消后软删除，重新分配时需先清理残留记录再 INSERT，
     * 否则主键冲突。
     */
    @Delete("DELETE FROM mate_skill WHERE name = #{name} AND deleted != 0")
    int clearSoftDeletedByName(@Param("name") String name);
}
