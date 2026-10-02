package com.sky.service.impl;


import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Setmeal;
import com.sky.exception.BaseException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 新增菜品和对应的口味
     *
     * @param dishDTO
     */
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {

        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //向菜品表插入1条数据
        dishMapper.insert(dish);//后绪步骤实现

        //获取insert语句生成的主键值
        Long dishId = dish.getId();

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);//后绪步骤实现
        }
    }

    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO
     * @return
     */
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);//后绪步骤实现
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Autowired
    private SetmealDishMapper setmealDishMapper;
    /**
     * 菜品批量删除
     *
     * @param ids
     */
    @Transactional//事务
    public void deleteBatch(List<Long> ids) {
        //判断当前菜品是否能够删除---是否存在起售中的菜品？？
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);//后绪步骤实现
            if (dish.getStatus() == StatusConstant.ENABLE) {
                //当前菜品处于起售中，不能删除
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }

        //判断当前菜品是否能够删除---是否被套餐关联了？？
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            //当前菜品被套餐关联了，不能删除
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        //删除菜品表中的菜品数据
        for (Long id : ids) {
            dishMapper.deleteById(id);//后绪步骤实现
            //删除菜品关联的口味数据
            dishFlavorMapper.deleteByDishId(id);//后绪步骤实现
        }
    }

    /**
     * 根据id查询菜品
     * @param id
     * @return
     */
    @Override
    public DishVO getByIdWithFlavor(Long id) {
        //根据id查询菜品数据
        Dish dish = dishMapper.getById(id);

        //根据菜品id查询口味数据
        List<DishFlavor> dishFlavors = dishFlavorMapper.getByDishId(id);
        //将查询到的数据封装到VO
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavors);

        return dishVO;
    }

    /**
     * 修改菜品
     * @param dishDTO
     */
    @Override
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //修改菜品基本信息
        dishMapper.update(dish);

        //删除原有口味数据
        dishFlavorMapper.deleteByDishId(dish.getId());

        //重新插入口味数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dish.getId());
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    @Override
    public List<Dish> list(Long categoryId, String name) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .name(name)
                .status(StatusConstant.ENABLE)
                .build();
        return dishMapper.list(dish);
    }



    /**
     * 菜品起售、停售；停售时同步停售关联套餐。
     */
    @Override
    @Transactional
    public void startOrStop(Integer status, List<Long> ids) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException("售卖状态只能为0或1");
        }
        if (ids == null || ids.isEmpty()
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BaseException("请选择有效的菜品");
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(ids);
        // 先检查所有菜品，避免批量请求包含无效ID时只修改一部分。
        for (Long id : uniqueIds) {
            if (dishMapper.getById(id) == null) {
                throw new BaseException("菜品不存在或已被删除");
            }
        }
        for (Long id : uniqueIds) {
            dishMapper.update(Dish.builder().id(id).status(status).build());
        }

        if (StatusConstant.DISABLE.equals(status)) {
            List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(new ArrayList<>(uniqueIds));
            if (setmealIds != null && !setmealIds.isEmpty()) {
                // 同一个套餐可能关联多道选中菜品，只需更新一次。
                for (Long setmealId : new LinkedHashSet<>(setmealIds)) {
                    setmealMapper.update(Setmeal.builder()
                            .id(setmealId)
                            .status(StatusConstant.DISABLE)
                            .build());
                }
            }
        }
    }

    /** 条件查询菜品及其口味。 */
    @Override
    public List<DishVO> listWithFlavor(Dish dish) {
        List<DishVO> result = new ArrayList<>();
        for (Dish item : dishMapper.list(dish)) {
            DishVO vo = new DishVO();
            BeanUtils.copyProperties(item, vo);
            vo.setFlavors(dishFlavorMapper.getByDishId(item.getId()));
            result.add(vo);
        }
        return result;
    }

}
