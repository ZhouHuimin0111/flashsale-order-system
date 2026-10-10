package io.github.zhouhuimin.flashsale.social;

import io.github.zhouhuimin.flashsale.auth.CurrentUser;
import io.github.zhouhuimin.flashsale.api.ApiError;
import io.github.zhouhuimin.flashsale.config.RedisConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/social")
public class SocialController {
    private final StringRedisTemplate redis;
    private final JdbcTemplate db;
    private static final List<Map<String,Object>> POSTS = List.of(
        Map.of("id",1,"title","把好运留给早起的人","body","咖啡月卡上新。为每天的开发加一点能量。","author","FlashSale 编辑部"),
        Map.of("id",2,"title","这个周末，一起补给","body","双人轻食券限量开放，发现附近的小确幸。","author","城市生活指南"),
        Map.of("id",3,"title","我的开发桌面进化记","body","数码配件体验券，给桌面一个小小的升级。","author","开发者日常"));
    public SocialController(StringRedisTemplate redis, JdbcTemplate db) { this.redis=redis; this.db=db; }
    @GetMapping("/posts") public List<Map<String,Object>> posts(HttpServletRequest req) {
        CurrentUser user=(CurrentUser)req.getAttribute("currentUser");
        return POSTS.stream().map(post -> {
            var view=new java.util.HashMap<>(post); String key=likes(((Number)post.get("id")).longValue());
            view.put("likes", java.util.Objects.requireNonNullElse(redis.opsForZSet().zCard(key), 0L));
            view.put("liked", user != null && redis.opsForZSet().score(key, String.valueOf(user.id())) != null); return (Map<String,Object>)view;
        }).toList();
    }
    private String likes(long postId) { validatePost(postId); return "social:{social}:likes:" + postId; }
    private void validatePost(long id) { if (id < 1 || id > 3) throw new ApiError(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "内容不存在"); }
    @PostMapping("/posts/{id}/like") public Map<String,Object> like(@PathVariable long id, HttpServletRequest req) {
        var result=redis.execute(RedisConfig.script("like.lua", List.class), List.of(likes(id), "social:{social}:ranking"), String.valueOf(CurrentUser.from(req).id()), String.valueOf(id));
        if (result == null) throw new IllegalStateException("No like result");
        return Map.of("liked", "1".equals(result.getFirst().toString()), "likes", Long.parseLong(result.get(1).toString()));
    }
    @GetMapping("/posts/{id}/likers") public List<Map<String,Object>> likers(@PathVariable long id) {
        Set<String> ids=redis.opsForZSet().range(likes(id), 0, 4);
        if (ids == null) return List.of();
        return ids.stream().flatMap(uid -> db.queryForList("SELECT id,username FROM app_user WHERE id=?", Long.parseLong(uid)).stream()).toList();
    }
    @GetMapping("/posts/ranking") public List<Map<String,Object>> ranking() {
        var tuples=redis.opsForZSet().reverseRangeWithScores("social:{social}:ranking",0,9);
        if (tuples==null) return List.of();
        return tuples.stream().map(t -> Map.<String,Object>of("postId",Long.parseLong(t.getValue()), "likes", t.getScore())).toList();
    }
    @GetMapping("/users") public List<Map<String,Object>> users(HttpServletRequest req) {
        long userId=CurrentUser.from(req).id();
        return db.queryForList("SELECT id,username FROM app_user WHERE id<>? ORDER BY id DESC LIMIT 50",userId).stream().map(row -> {
            row.put("following",Boolean.TRUE.equals(redis.opsForSet().isMember(follows(userId),row.get("id").toString()))); return row;
        }).toList();
    }
    private String follows(long uid) { return "social:{social}:follows:"+uid; }
    @PutMapping("/follow/{id}") public Map<String,Boolean> follow(@PathVariable long id, HttpServletRequest req) { return changeFollow(id,req,true); }
    @DeleteMapping("/follow/{id}") public Map<String,Boolean> unfollow(@PathVariable long id, HttpServletRequest req) { return changeFollow(id,req,false); }
    private Map<String,Boolean> changeFollow(long id,HttpServletRequest req,boolean add) {
        long uid=CurrentUser.from(req).id();
        if(id==uid) throw new ApiError(HttpStatus.BAD_REQUEST,"INVALID_FOLLOW","不能关注自己");
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=?)",Boolean.class,id))) throw new ApiError(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","用户不存在");
        if(add) redis.opsForSet().add(follows(uid),String.valueOf(id)); else redis.opsForSet().remove(follows(uid),String.valueOf(id));
        return Map.of("following",add);
    }
    @GetMapping("/follow/{id}/common") public List<Map<String,Object>> common(@PathVariable long id,HttpServletRequest req) {
        Set<String> ids=redis.opsForSet().intersect(follows(CurrentUser.from(req).id()),follows(id));
        if(ids==null) return List.of();
        return ids.stream().limit(100).flatMap(uid -> db.queryForList("SELECT id,username FROM app_user WHERE id=?",Long.parseLong(uid)).stream()).toList();
    }
}
