package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(String openid);

    /**
     * 根据用户id查询用户，供订单支付获取openid。
     */
    @Select("select * from user where id = #{id}")
    User getById(Long id);

    /**
     * 插入数据
     * @param user
     */
    void insert(User user);


}
