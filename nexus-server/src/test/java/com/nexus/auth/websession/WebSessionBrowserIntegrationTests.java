package com.nexus.auth.websession;

import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in actual Chrome→Vite→HTTP→MySQL test. Never runs against a daily schema. */
@EnabledIfEnvironmentVariable(named="NEXUS_RUN_WEB_BROWSER", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1", "nexus.auth.session.cleanup-enabled=false", "nexus.auth.jwt.access-token-ttl=8s",
        "nexus.auth.web.secure-cookie=false", "nexus.auth.web.cookie-name=nexus_rt_dev",
        "nexus.auth.web.allowed-origins[0]=http://127.0.0.1:4174"})
class WebSessionBrowserIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Test void realBrowserCookieSessionSurvivesReloadRotatesAndRevokes() throws Exception {
        assertEquals("nexus_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        Path root=Path.of("..").toRealPath(), console=root.resolve("nexus-console");
        String node=Objects.requireNonNull(System.getenv("NEXUS_TEST_NODE"),"Explicit existing Node path is required");
        Path output=root.resolve("nexus-desktop/.runtime/web-live");Files.createDirectories(output);
        Path config=output.resolve("vite.config.mts");
        Files.writeString(config,"""
                import base from '../../../nexus-console/vite.config'
                export default {...base, cacheDir:'../nexus-desktop/.runtime/web-live/vite-cache', server:{...base.server,proxy:{'/api':{target:'http://127.0.0.1:%d',changeOrigin:true}}},
                  plugins:[...(base.plugins??[]),{name:'block-machine-live-test',configureServer(server){server.middlewares.use((req,res,next)=>{
                    if(/^\\/(v1|s)\\//.test(req.url??'')){res.statusCode=503;res.end();return}next()
                  })}}]}
                """.formatted(port));
        User user=new User();user.setEmail("web-live-"+UUID.randomUUID()+"@example.test");user.setDisplayName("web live test");
        String password=UUID.randomUUID().toString();user.setPasswordHash(passwords.encode(password));users.insert(user);
        Process vite=null, browser=null;
        try {
            vite=new ProcessBuilder(node,console.resolve("node_modules/vite/bin/vite.js").toString(),"--config",config.toString(),"--host","127.0.0.1","--port","4174","--strictPort")
                    .directory(console.toFile()).redirectErrorStream(true).redirectOutput(output.resolve("vite.log").toFile()).start();
            var client=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(java.time.Duration.ofSeconds(2)).build();boolean ready=false;
            for(int i=0;i<100;i++) {
                if(!vite.isAlive())break;
                try { var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:4174/login")).timeout(java.time.Duration.ofSeconds(2)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());if(response.statusCode()==200){ready=true;break;} }
                catch(java.io.IOException ignored) {}
                Thread.sleep(100);
            }
            assertTrue(ready,"Isolated Vite did not start; inspect local runtime log");
            ProcessBuilder builder=new ProcessBuilder(node,console.resolve("scripts/test-web-session-live.mjs").toString()).directory(console.toFile())
                    .redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
            builder.environment().put("NEXUS_WEB_TEST_ORIGIN","http://127.0.0.1:4174");builder.environment().put("NEXUS_WEB_TEST_EMAIL",user.getEmail());builder.environment().put("NEXUS_WEB_TEST_PASSWORD",password);
            browser=builder.start();assertTrue(browser.waitFor(90,TimeUnit.SECONDS),"Browser acceptance timed out");
            assertEquals(0,browser.exitValue(),"Browser acceptance failed; inspect private runtime log");
            assertTrue(Files.readString(output.resolve("browser.log")).contains("LIVE_WEB_ACCEPTANCE_OK"));
        } finally {
            if(browser!=null && browser.isAlive()){browser.destroy();if(!browser.waitFor(3,TimeUnit.SECONDS))browser.destroyForcibly();}
            if(vite!=null && vite.isAlive()){vite.destroy();if(!vite.waitFor(3,TimeUnit.SECONDS))vite.destroyForcibly();}
            jdbc.update("DELETE r FROM refresh_tokens r JOIN auth_sessions s ON s.id=r.session_id WHERE s.user_id=?",user.getId());
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",user.getId());users.deleteById(user.getId());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,user.getId()));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id=?",Integer.class,user.getId()));
        }
    }
}
