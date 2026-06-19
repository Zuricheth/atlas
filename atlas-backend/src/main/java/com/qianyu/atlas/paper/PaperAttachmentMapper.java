package com.qianyu.atlas.paper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaperAttachmentMapper extends BaseMapper<PaperAttachment> {
    @Select("""
            select *
            from paper_attachment
            where deleted = 1
              and user_id = #{userId}
            order by updated_at desc
            """)
    List<PaperAttachment> selectDeleted(@Param("userId") Long userId);

    @Select("""
            select *
            from paper_attachment
            where deleted = 1
            order by updated_at desc
            """)
    List<PaperAttachment> selectAllDeleted();

    @Select("""
            select *
            from paper_attachment
            where deleted = 1
              and user_id = #{userId}
              and id = #{attachmentId}
            limit 1
            """)
    PaperAttachment selectDeletedById(@Param("userId") Long userId, @Param("attachmentId") Long attachmentId);

    @Update("""
            update paper_attachment
            set deleted = 0, updated_at = current_timestamp
            where id = #{attachmentId}
              and user_id = #{userId}
              and deleted = 1
            """)
    int restore(@Param("userId") Long userId, @Param("attachmentId") Long attachmentId);

    @Delete("""
            delete from paper_attachment
            where id = #{attachmentId}
              and user_id = #{userId}
              and deleted = 1
            """)
    int purgeDeleted(@Param("userId") Long userId, @Param("attachmentId") Long attachmentId);
}
