package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Category;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.BaseException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import com.sky.vo.DishItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import org.springframework.util.StringUtils;

/**
 * 套餐业务实现
 */
@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private CategoryMapper categoryMapper;

    /**
     * 新增套餐，同时需要保存套餐和菜品的关联关系
     * @param setmealDTO
     */
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        validateAndFillDishes(setmealDTO, null, false);
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        // 新增状态由后端保证，不能通过请求直接起售。
        setmeal.setId(null);
        setmeal.setStatus(StatusConstant.DISABLE);

        //向套餐表插入数据
        setmealMapper.insert(setmeal);

        //获取生成的套餐id
        Long setmealId = setmeal.getId();

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });

        //保存套餐和菜品的关联关系
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        if (setmealPageQueryDTO.getPage() < 1 || setmealPageQueryDTO.getPageSize() < 1) {
            throw new BaseException("页码和每页条数必须大于0");
        }
        PageHelper.startPage(setmealPageQueryDTO.getPage(),setmealPageQueryDTO.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 批量删除套餐
     * @param ids
     */
    @Transactional
    public void deleteBatch(List<Long> ids) {
        validateIds(ids);
        ids.forEach(id -> {
            Setmeal setmeal = requireSetmeal(id);
            if(StatusConstant.ENABLE.equals(setmeal.getStatus())){
                //起售中的套餐不能删除
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        });

        ids.forEach(setmealId -> {
            //删除套餐菜品关系表中的数据
            setmealDishMapper.deleteBySetmealId(setmealId);
            //再删除套餐主表数据
            setmealMapper.deleteById(setmealId);
        });
    }

    /**
     * 根据id查询套餐和关联的菜品数据
     * @param id
     * @return
     */
    @Override
    public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal = requireSetmeal(id);
        List<SetmealDish> setmealDishes = setmealDishMapper.getBySetmealId(id);

        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal, setmealVO);
        setmealVO.setSetmealDishes(setmealDishes);

        return setmealVO;
    }

    /**
     * 修改套餐
     * @param setmealDTO
     */
    @Override
    @Transactional
    public void update(SetmealDTO setmealDTO) {
        if (setmealDTO == null) {
            throw new BaseException("套餐参数不能为空");
        }
        Setmeal existing = requireSetmeal(setmealDTO.getId());
        validateAndFillDishes(setmealDTO, existing.getId(), StatusConstant.ENABLE.equals(existing.getStatus()));
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        // 编辑基本信息保留原状态，起售停售统一走状态接口。
        setmeal.setStatus(existing.getStatus());
        setmeal.setDescription(setmealDTO.getDescription() == null ? "" : setmealDTO.getDescription());

        //1、修改套餐表，执行update
        setmealMapper.update(setmeal);

        //套餐id
        Long setmealId = setmealDTO.getId();

        //2、删除套餐和菜品的关联关系，操作setmeal_dish表，执行delete
        setmealDishMapper.deleteBySetmealId(setmealId);

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);
        });

        //3、重新插入套餐和菜品的关联关系，操作setmeal_dish表，执行insert
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 套餐起售、停售
     * @param status
     * @param ids
     */
    @Override
    @Transactional
    public void startOrStop(Integer status, List<Long> ids) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException("售卖状态只能为0或1");
        }
        validateIds(ids);
        Set<Long> uniqueIds = new LinkedHashSet<>(ids);
        // 全部检查通过后再修改；事务保证批量操作不会只成功一部分。
        for (Long id : uniqueIds) {
            requireSetmeal(id);
            if (StatusConstant.ENABLE.equals(status)) {
                List<SetmealDish> relations = setmealDishMapper.getBySetmealId(id);
                if (relations == null || relations.isEmpty()) {
                    throw new SetmealEnableFailedException("套餐没有菜品，无法起售");
                }
                for (SetmealDish relation : relations) {
                    Dish dish = dishMapper.getById(relation.getDishId());
                    if (dish == null || !StatusConstant.ENABLE.equals(dish.getStatus())) {
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                }
            }
        }
        for (Long id : uniqueIds) {
            setmealMapper.update(Setmeal.builder().id(id).status(status).build());
        }
    }

    private Setmeal requireSetmeal(Long id) {
        if (id == null || id <= 0) {
            throw new BaseException("请选择有效的套餐");
        }
        Setmeal setmeal = setmealMapper.getById(id);
        if (setmeal == null) {
            throw new BaseException("套餐不存在或已被删除");
        }
        return setmeal;
    }

    private void validateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BaseException("请选择有效的套餐");
        }
    }

    /** 校验套餐，并从数据库补齐菜品名称和价格，避免保存前端过期或伪造的信息。 */
    private void validateAndFillDishes(SetmealDTO dto, Long currentId, boolean onSale) {
        if (dto == null || !StringUtils.hasText(dto.getName()) || dto.getName().trim().length() > 32) {
            throw new BaseException("套餐名称不能为空且不能超过32个字符");
        }
        dto.setName(dto.getName().trim());
        Setmeal sameName = setmealMapper.getByName(dto.getName());
        if (sameName != null && !Objects.equals(sameName.getId(), currentId)) {
            throw new BaseException("套餐名称已存在");
        }
        Category category = dto.getCategoryId() == null ? null : categoryMapper.getById(dto.getCategoryId());
        if (category == null || !Integer.valueOf(2).equals(category.getType())) {
            throw new BaseException("请选择有效的套餐分类");
        }
        BigDecimal price = dto.getPrice();
        if (price == null || price.signum() <= 0 || price.compareTo(new BigDecimal("99999999.99")) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new BaseException("套餐价格必须大于0、不超过99999999.99且最多两位小数");
        }
        if (!StringUtils.hasText(dto.getImage()) || dto.getImage().length() > 255) {
            throw new BaseException("套餐图片不能为空且路径不能超过255个字符");
        }
        if (dto.getDescription() != null && dto.getDescription().length() > 255) {
            throw new BaseException("套餐描述不能超过255个字符");
        }
        List<SetmealDish> dishes = dto.getSetmealDishes();
        if (dishes == null || dishes.isEmpty()) {
            throw new BaseException("套餐必须包含菜品");
        }
        Set<Long> dishIds = new HashSet<>();
        for (SetmealDish relation : dishes) {
            if (relation == null || relation.getDishId() == null || relation.getCopies() == null
                    || relation.getCopies() < 1 || relation.getCopies() > 99) {
                throw new BaseException("请选择菜品并填写1至99之间的份数");
            }
            if (!dishIds.add(relation.getDishId())) {
                throw new BaseException("同一道菜不能重复添加，请调整份数");
            }
            Dish dish = dishMapper.getById(relation.getDishId());
            if (dish == null) {
                throw new BaseException("所选菜品不存在或已被删除");
            }
            if (onSale && !StatusConstant.ENABLE.equals(dish.getStatus())) {
                throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
            }
            relation.setName(dish.getName());
            relation.setPrice(dish.getPrice());
        }
    }


    @Override
    public List<Setmeal> list(Setmeal setmeal) {
        return setmealMapper.list(setmeal);
    }

    @Override
    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }

}
