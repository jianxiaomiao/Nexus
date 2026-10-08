package com.nexus.shortlink.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortLinkServiceTests {
    @Mock ShortLinkMapper shortLinkMapper;
    @Mock ApiKeyMapper apiKeyMapper;
    @Mock ApplicationMapper applicationMapper;
    @Mock Clock clock;
    @InjectMocks ShortLinkService service;

    @BeforeAll
    static void initializeMappings() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ApplicationMapper.class.getName());
        assistant.setCurrentNamespace(ApplicationMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, Application.class);
    }

    @Test
    void anotherOwnerCannotListKeyLinks() {
        ApiKey key = new ApiKey();
        key.setApplicationId(3L);
        when(apiKeyMapper.selectById(7L)).thenReturn(key);
        when(applicationMapper.selectOne(any())).thenReturn(null);

        assertThrows(ShortLinkNotFoundException.class, () -> service.listMyShortLinks(99L, 7L, 1, 10));
        verifyNoInteractions(shortLinkMapper);
    }
}
