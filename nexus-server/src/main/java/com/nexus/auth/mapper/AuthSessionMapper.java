package com.nexus.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.auth.entity.AuthSession;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface AuthSessionMapper extends BaseMapper<AuthSession> {
    @Select("SELECT * FROM auth_sessions WHERE id = #{id} FOR UPDATE")
    AuthSession selectForUpdate(@Param("id") long id);

    @Update("UPDATE auth_sessions SET revoked_at = #{now} WHERE id = #{id} AND revoked_at IS NULL")
    int revoke(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE auth_sessions SET revoked_at = #{now} WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeAll(@Param("userId") long userId, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM auth_sessions WHERE expires_at <= #{cutoff} ORDER BY expires_at, id LIMIT #{limit}")
    List<AuthSession> selectExpiredCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Select("SELECT * FROM auth_sessions WHERE revoked_at <= #{cutoff} ORDER BY revoked_at, id LIMIT #{limit}")
    List<AuthSession> selectRevokedCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
