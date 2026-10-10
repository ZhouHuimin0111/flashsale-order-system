package io.github.zhouhuimin.flashsale.order;

import io.github.zhouhuimin.flashsale.api.ApiError;
import io.github.zhouhuimin.flashsale.auth.AuthService;
import io.github.zhouhuimin.flashsale.config.RedisConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringJUnitConfig(CoreConcurrencyIT.Config.class)
class CoreConcurrencyIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource() {
            var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            Flyway.configure().dataSource(ds).load().migrate(); return ds;
        }
        @Bean JdbcTemplate db(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean OrderWriter writer(JdbcTemplate db) { return new OrderWriter(db); }
        @Bean(destroyMethod="destroy") LettuceConnectionFactory factory() { return new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory f) { return new StringRedisTemplate(f); }
        @Bean(destroyMethod="shutdown") RedissonClient locks() { return new RedisConfig().redisson(REDIS.getHost(),REDIS.getMappedPort(6379),""); }
    }
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired OrderWriter writer;
    @Autowired RedissonClient locks;
    private final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    private static final AtomicInteger IDS=new AtomicInteger(100);
    private long coupon;
    @BeforeEach void setup() {
        coupon=IDS.incrementAndGet();
        db.update("INSERT INTO coupon(id,title,price_cents,initial_stock,stock,starts_at,ends_at) VALUES (?,'test',100,10,10,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 DAY)",coupon);
        for(int i=1;i<=100;i++) db.update("INSERT IGNORE INTO app_user(id,username,password_hash) VALUES (?,?,?)",(long)i,"test"+i,"unused");
        redis.execute(RedisConfig.script("publish.lua",Long.class),List.of(OrderKeys.inventory(coupon),OrderKeys.outbox(coupon)),"10","0",String.valueOf(System.currentTimeMillis()+3600000));
    }
    private void parallel(List<Callable<Void>> tasks) throws Exception {
        try(var pool=Executors.newFixedThreadPool(20)) { for(var result:pool.invokeAll(tasks)) result.get(60,TimeUnit.SECONDS); }
    }
    @Test void luaAdmitsTenDistinctBuyersUnderTwoHundredConcurrentRequests() throws Exception {
        var service=new OrderService(redis,db,metrics);
        AtomicInteger accepted=new AtomicInteger(),duplicates=new AtomicInteger(),soldOut=new AtomicInteger();
        List<Callable<Void>> tasks=new ArrayList<>();
        for(long uid=1;uid<=100;uid++) for(int repeat=0;repeat<2;repeat++) {
            final long user=uid;
            tasks.add(()->{ try { var r=service.reserve(coupon,user); if(r.duplicate()) duplicates.incrementAndGet(); else accepted.incrementAndGet(); }
                catch(ApiError e) { assertEquals("SOLD_OUT",e.code()); soldOut.incrementAndGet(); } return null; });
        }
        parallel(tasks);
        assertEquals(10,accepted.get()); assertEquals(10,duplicates.get()); assertEquals(180,soldOut.get());
        assertEquals("0",redis.opsForHash().get(OrderKeys.inventory(coupon),"stock"));
        assertEquals(10,redis.opsForHash().size(OrderKeys.buyers(coupon)));
        assertEquals(11,redis.opsForStream().size(OrderKeys.outbox(coupon))); // seed plus ten recoverable events
    }
    @Test void distributedConsumersDeduplicateAfterCommitAndBeforeRedisUpdate() throws Exception {
        var service=new OrderService(redis,db,metrics);
        var reservation=service.reserve(coupon,1);
        var event=new OrderEvent(reservation.orderId(),1,coupon);
        // Simulate a crash after DB commit, before updating Redis/ACKing MQ.
        assertEquals("CONFIRMED",writer.persist(event));
        assertEquals("PENDING",redis.opsForHash().get(OrderKeys.status(coupon),event.orderId()));
        var secondClient=new RedisConfig().redisson(REDIS.getHost(),REDIS.getMappedPort(6379),"");
        try {
            var a=new OrderConsumer(writer,locks,redis,metrics);
            var b=new OrderConsumer(writer,secondClient,redis,metrics);
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<40;i++) { final int index=i; tasks.add(()->{ (index%2==0?a:b).consume(event); return null; }); }
            parallel(tasks);
        } finally { secondClient.shutdown(); }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM coupon_order WHERE coupon_id=?",Integer.class,coupon));
        assertEquals(9,db.queryForObject("SELECT stock FROM coupon WHERE id=?",Integer.class,coupon));
        assertEquals("CONFIRMED",redis.opsForHash().get(OrderKeys.status(coupon),event.orderId()));
    }
    @Test void databaseBoundaryNeverOversellsEvenWithoutAdmission() throws Exception {
        var handler=new OrderConsumer(writer,locks,redis,metrics);
        List<Callable<Void>> tasks=new ArrayList<>();
        for(long uid=1;uid<=40;uid++) { final long user=uid; tasks.add(()->{
            var event=new OrderEvent(UUID.randomUUID().toString(),user,coupon);
            for(int retry=0;;retry++) { try { handler.consume(event); break; } catch(IllegalStateException e) { if(retry>=7) throw e; } }
            return null;
        }); }
        parallel(tasks);
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM coupon_order WHERE coupon_id=?",Integer.class,coupon));
        assertEquals(0,db.queryForObject("SELECT stock FROM coupon WHERE id=?",Integer.class,coupon));
        assertEquals(30,db.queryForObject("SELECT COUNT(*) FROM order_rejection WHERE coupon_id=?",Integer.class,coupon));
    }
    @Test void insertFailureRollsBackStockDeduction() {
        assertThrows(Exception.class,()->writer.persist(new OrderEvent(UUID.randomUUID().toString(),999999,coupon)));
        assertEquals(10,db.queryForObject("SELECT stock FROM coupon WHERE id=?",Integer.class,coupon));
        assertEquals(0,db.queryForObject("SELECT version FROM coupon WHERE id=?",Integer.class,coupon));
    }
    @Test void uninitializedOrExpiredSaleIsRejectedWithoutOutboxWrite() {
        var service=new OrderService(redis,db,metrics);
        assertEquals("NOT_READY",assertThrows(ApiError.class,()->service.reserve(999999,1)).code());
        redis.opsForHash().put(OrderKeys.inventory(coupon),"endsAt","1");
        assertEquals("ENDED",assertThrows(ApiError.class,()->service.reserve(coupon,1)).code());
        assertEquals(1,redis.opsForStream().size(OrderKeys.outbox(coupon)));
    }
    @Test void sessionsRefreshAcrossInstancesAndLogoutInvalidatesBoth() {
        String token="test-shared-token";
        redis.execute(RedisConfig.script("session-create.lua",Long.class),List.of("session:"+token),"1","test1","USER","3");
        var a=new AuthService(db,redis,""); var b=new AuthService(db,redis,"");
        assertEquals(1,a.resolve(token).id()); assertEquals(1,b.resolve(token).id());
        assertTrue(redis.getExpire("session:"+token)>1700);
        a.logout(token); assertNull(b.resolve(token));
    }
    @Test void responseLossCanRecoverReservationWithoutKnowingOrderId() {
        var service=new OrderService(redis,db,metrics);
        assertTrue(service.pending(1).stream().noneMatch(row->row.get("couponId").equals(coupon)));
        var reservation=service.reserve(coupon,1);
        assertTrue(service.pending(1).stream().anyMatch(row->row.get("orderId").equals(reservation.orderId())));
    }
    @Test void rejectionIsQueryableAndClosesInconsistentAdmissionStock() throws Exception {
        var service=new OrderService(redis,db,metrics);
        var reservation=service.reserve(coupon,1);
        db.update("UPDATE coupon SET stock=0 WHERE id=?",coupon);
        new OrderConsumer(writer,locks,redis,metrics).consume(new OrderEvent(reservation.orderId(),1,coupon));
        assertEquals("REJECTED",service.status(coupon,reservation.orderId(),1).get("status"));
        assertEquals("0",redis.opsForHash().get(OrderKeys.inventory(coupon),"stock"));
        assertEquals("SOLD_OUT",assertThrows(ApiError.class,()->service.reserve(coupon,2)).code());
    }
    @Test void administrativeRedrivePreservesOrderIdentityAndOnlyAllowsPending() throws Exception {
        var service=new OrderService(redis,db,metrics);
        var r=service.reserve(coupon,1);
        var keys=List.of(OrderKeys.owners(coupon),OrderKeys.status(coupon),OrderKeys.outbox(coupon));
        assertEquals(1L,redis.execute(RedisConfig.script("redrive.lua",Long.class),keys,r.orderId(),String.valueOf(coupon)));
        assertEquals(3L,redis.opsForStream().size(OrderKeys.outbox(coupon)));
        new OrderConsumer(writer,locks,redis,metrics).consume(new OrderEvent(r.orderId(),1,coupon));
        assertEquals(0L,redis.execute(RedisConfig.script("redrive.lua",Long.class),keys,r.orderId(),String.valueOf(coupon)));
        assertEquals(0L,redis.execute(RedisConfig.script("redrive.lua",Long.class),keys,UUID.randomUUID().toString(),String.valueOf(coupon)));
    }
}
