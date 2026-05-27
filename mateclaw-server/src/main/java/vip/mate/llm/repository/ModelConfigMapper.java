package vip.mate.llm.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.llm.model.ModelConfigEntity;

@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfigEntity> {

    /**
     * 物理恢复逻辑删除的记录（绕过 @TableLogic 过滤器），
     * 用于解决平台同步时 selectById 查不到已删除记录但主键仍存在的冲突
     */
    @Update("UPDATE mate_model_config SET deleted = 0 WHERE id = #{id}")
    int physicalRestore(@Param("id") Long id);
}
