package com.qianyu.atlas.note;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NoteMapper extends BaseMapper<Note> {
    @Select("""
            select *
            from note
            where deleted = 0
              and user_id = #{userId}
              and (
                  lower(title) like lower(concat('%', #{keyword}, '%'))
                  or lower(search_text) like lower(concat('%', #{keyword}, '%'))
              )
            order by
              case
                when lower(title) = lower(#{keyword}) then 0
                when lower(title) like lower(concat('%', #{keyword}, '%')) then 1
                when lower(summary) like lower(concat('%', #{keyword}, '%')) then 2
                when lower(search_text) like lower(concat('%', #{keyword}, '%')) then 3
                else 4
              end,
              updated_at desc
            limit #{limit}
            """)
    List<Note> search(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("limit") int limit);

    @Select("""
            select *
            from note
            where deleted = 1
              and user_id = #{userId}
            order by updated_at desc
            """)
    List<Note> selectDeleted(@Param("userId") Long userId);

    @Select("""
            select *
            from note
            where deleted = 1
            order by updated_at desc
            """)
    List<Note> selectAllDeleted();

    @Update("""
            update note
            set deleted = 0, updated_at = current_timestamp
            where id = #{noteId}
              and user_id = #{userId}
              and deleted = 1
            """)
    int restore(@Param("userId") Long userId, @Param("noteId") Long noteId);

    @Delete("""
            delete from note
            where id = #{noteId}
              and user_id = #{userId}
              and deleted = 1
            """)
    int purgeDeleted(@Param("userId") Long userId, @Param("noteId") Long noteId);
}
