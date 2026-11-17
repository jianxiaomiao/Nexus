package com.nexus.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.auth.entity.RefreshToken;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
    @Select("SELECT * FROM refresh_tokens WHERE token_hash = #{hash}")
    RefreshToken selectByHash(@Param("hash") String hash);

    @Select("SELECT * FROM refresh_tokens WHERE session_id = #{sessionId} AND token_hash = #{hash} FOR UPDATE")
    RefreshToken selectForUpdate(@Param("sessionId") long sessionId, @Param("hash") String hash);

    @Update("UPDATE refresh_tokens SET consumed_at = #{now} WHERE id = #{id} AND consumed_at IS NULL")
    int consume(@Param("id") long id, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM refresh_tokens WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") long sessionId);
}
