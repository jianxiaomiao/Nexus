package com.nexus.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.user.entity.User;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    User selectForUpdate(@Param("id") long id);

    @Update("UPDATE users SET status = #{status}, updated_at = #{now} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") int status, @Param("now") LocalDateTime now);
}
