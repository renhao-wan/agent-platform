package io.github.renhaowan.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.sql.init.mode=never")
class PlatformApplicationTests {

    @Test
    void contextLoads() {
    }
}
