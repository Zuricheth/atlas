package com.qianyu.atlas.library;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LibraryItemMapper extends BaseMapper<LibraryItem> {
    @Select("""
            select *
            from library_item
            where deleted = 1
              and user_id = #{userId}
            order by updated_at desc
            """)
    List<LibraryItem> selectDeleted(@Param("userId") Long userId);

    @Select("""
            select *
            from library_item
            where deleted = 1
            order by updated_at desc
            """)
    List<LibraryItem> selectAllDeleted();

    @Select("""
            select *
            from library_item
            where deleted = 1
              and user_id = #{userId}
              and id = #{itemId}
            limit 1
            """)
    LibraryItem selectDeletedById(@Param("userId") Long userId, @Param("itemId") Long itemId);

    @Update("""
            update library_item
            set deleted = 0, updated_at = current_timestamp
            where id = #{itemId}
              and user_id = #{userId}
              and deleted = 1
            """)
    int restore(@Param("userId") Long userId, @Param("itemId") Long itemId);

    @Delete("""
            delete from library_item
            where id = #{itemId}
              and user_id = #{userId}
              and deleted = 1
            """)
    int purgeDeleted(@Param("userId") Long userId, @Param("itemId") Long itemId);
}
