package com.sky;

import com.sky.context.BaseContext;
import com.sky.entity.Setmeal;
import com.sky.exception.BaseException;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.mapper.SetmealMapper;
import com.sky.service.DishService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses only uniquely named fixtures; Redis and authentication are mocked. */
@SpringBootTest(properties = "spring.data.redis.repositories.enabled=false")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SKY_RUN_DB_TESTS", matches = "true")
class DishStatusTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DishService dishService;
    @MockBean(name = "redisTemplate") private RedisTemplate redisTemplate;
    @MockBean private JwtTokenAdminInterceptor adminInterceptor;
    @SpyBean(proxyTargetAware = true) private SetmealMapper setmealMapper;

    private Long dish1, dish2, meal1, meal2;

    @BeforeEach
    void setUp() throws Exception {
        // Reset the underlying spy, not just the AutoFill AOP wrapper.
        Object mapperSpy = AopTestUtils.getUltimateTargetObject(setmealMapper);
        reset(mapperSpy);
        doAnswer(invocation -> {
            BaseContext.setCurrentId(1L);
            return true;
        }).when(adminInterceptor).preHandle(any(), any(), any());
        when(redisTemplate.keys("dish_*")).thenReturn(Collections.singleton("dish_test_fixture"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long dishCategory = jdbc.queryForObject("select id from category where type=1 limit 1", Long.class);
        Long mealCategory = jdbc.queryForObject("select id from category where type=2 limit 1", Long.class);
        dish1 = insert("dish", "ds_d1_" + suffix, dishCategory);
        dish2 = insert("dish", "ds_d2_" + suffix, dishCategory);
        meal1 = insert("setmeal", "ds_m1_" + suffix, mealCategory);
        meal2 = insert("setmeal", "ds_m2_" + suffix, mealCategory);
        relation(meal1, dish1);
        relation(meal1, dish2);
        relation(meal2, dish2);
    }

    private Long insert(String table, String name, Long category) {
        jdbc.update("insert into " + table + " (name,category_id,price,image,status,create_time,update_time,create_user,update_user) "
                + "values (?,?,10,'dish-status-test',1,'2020-01-01','2020-01-01',1,1)", name, category);
        return jdbc.queryForObject("select id from " + table + " where name=?", Long.class, name);
    }

    private void relation(Long meal, Long dish) {
        jdbc.update("insert into setmeal_dish(setmeal_id,dish_id,name,price,copies) values (?,?,'fixture',10,1)", meal, dish);
    }

    @AfterEach
    void cleanUp() {
        // Only delete this test's IDs, including partial setup if it failed.
        for (Long id : Arrays.asList(meal1, meal2)) {
            if (id != null) {
                jdbc.update("delete from setmeal_dish where setmeal_id=?", id);
                jdbc.update("delete from setmeal where id=?", id);
            }
        }
        for (Long id : Arrays.asList(dish1, dish2)) {
            if (id != null) jdbc.update("delete from dish where id=?", id);
        }
        BaseContext.removeCurrentId();
    }

    private int readStatus(String table, Long id) {
        return jdbc.queryForObject("select status from " + table + " where id=?", Integer.class, id);
    }

    private void change(int value, String ids) throws Exception {
        mvc.perform(post("/admin/dish/status/" + value).param("id", ids))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void stopAlsoStopsRelatedMealAndFillsAuditFields() throws Exception {
        change(0, dish1.toString());
        assertEquals(0, readStatus("dish", dish1));
        assertEquals(0, readStatus("setmeal", meal1));
        assertEquals(1, readStatus("setmeal", meal2));
        assertEquals(1L, jdbc.queryForObject("select update_user from dish where id=?", Long.class, dish1));
        assertTrue(jdbc.queryForObject("select update_time > '2020-01-01' from dish where id=?", Boolean.class, dish1));
        assertTrue(jdbc.queryForObject("select update_time > '2020-01-01' from setmeal where id=?", Boolean.class, meal1));
        assertEquals(10, jdbc.queryForObject("select price from dish where id=?", Integer.class, dish1));
        verify(redisTemplate).delete(Collections.singleton("dish_test_fixture"));
    }

    @Test
    void batchStopDeduplicatesDishesAndMeals() throws Exception {
        change(0, dish1 + "," + dish2 + "," + dish1);
        assertEquals(0, readStatus("dish", dish1));
        assertEquals(0, readStatus("dish", dish2));
        assertEquals(0, readStatus("setmeal", meal1));
        assertEquals(0, readStatus("setmeal", meal2));
        verify(setmealMapper, times(1)).update(argThat((Setmeal meal) -> meal1.equals(meal.getId())));
    }

    @Test
    void batchStartDoesNotAutomaticallyStartMeals() throws Exception {
        change(0, dish1 + "," + dish2);
        change(1, dish1 + "," + dish2);
        assertEquals(1, readStatus("dish", dish1));
        assertEquals(1, readStatus("dish", dish2));
        assertEquals(0, readStatus("setmeal", meal1));
        assertEquals(0, readStatus("setmeal", meal2));
    }

    @Test
    void repeatedStopIsSafe() throws Exception {
        change(0, dish1.toString());
        change(0, dish1.toString());
        assertEquals(0, readStatus("dish", dish1));
        assertEquals(0, readStatus("setmeal", meal1));
    }

    @Test
    void stopUnrelatedDishWithNoCachedKeys() throws Exception {
        jdbc.update("delete from setmeal_dish where dish_id=?", dish1);
        when(redisTemplate.keys("dish_*")).thenReturn(Collections.emptySet());
        change(0, dish1.toString());
        assertEquals(0, readStatus("dish", dish1));
        assertEquals(1, readStatus("setmeal", meal1));
        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void invalidStatusDoesNotWriteOrEvictCache() throws Exception {
        mvc.perform(post("/admin/dish/status/2").param("id", dish1.toString()))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, readStatus("dish", dish1));
        verify(redisTemplate, never()).keys(anyString());
    }

    @Test
    void missingDishInBatchLeavesExistingDishUnchanged() throws Exception {
        mvc.perform(post("/admin/dish/status/0").param("id", dish1 + "," + Long.MAX_VALUE))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, readStatus("dish", dish1));
        assertEquals(1, readStatus("setmeal", meal1));
        verify(redisTemplate, never()).keys(anyString());
    }

    @Test
    void invalidIdsAreRejectedBeforeWriting() {
        assertThrows(BaseException.class, () -> dishService.startOrStop(0, null));
        assertThrows(BaseException.class, () -> dishService.startOrStop(0, Collections.emptyList()));
        assertThrows(BaseException.class, () -> dishService.startOrStop(0, Arrays.asList(dish1, null)));
        assertThrows(BaseException.class, () -> dishService.startOrStop(0, Arrays.asList(dish1, 0L)));
        assertThrows(BaseException.class, () -> dishService.startOrStop(0, Arrays.asList(dish1, -1L)));
        assertThrows(BaseException.class, () -> dishService.startOrStop(null, Collections.singletonList(dish1)));
        assertEquals(1, readStatus("dish", dish1));
    }

    @Test
    void missingOrMalformedRequestParameterReturns400() throws Exception {
        mvc.perform(post("/admin/dish/status/0")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/dish/status/0").param("id", "abc")).andExpect(status().isBadRequest());
        assertEquals(1, readStatus("dish", dish1));
    }

    @Test
    void mealUpdateFailureRollsBackDishUpdate() {
        BaseContext.setCurrentId(1L);
        doThrow(new BaseException("forced rollback test"))
                .when(setmealMapper).update(any(Setmeal.class));
        assertThrows(BaseException.class, () -> dishService.startOrStop(0, Collections.singletonList(dish1)));
        assertEquals(1, readStatus("dish", dish1));
        assertEquals(1, readStatus("setmeal", meal1));
    }
}
