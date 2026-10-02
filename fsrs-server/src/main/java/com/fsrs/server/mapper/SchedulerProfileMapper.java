package com.fsrs.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fsrs.server.domain.SchedulerProfile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SchedulerProfileMapper extends BaseMapper<SchedulerProfile> {

    @Select("SELECT * FROM scheduler_profile WHERE user_id = #{userId}")
    SchedulerProfile findByUserId(@Param("userId") long userId);

    /**
     * {@code review_log} row count for one user - the sample size an optimizer job would
     * need before its result can be trusted. Exposed to the frontend so the UI can tell
     * the user "you have N reviews" instead of failing later inside the optimizer.
     */
    @Select("SELECT COUNT(*) FROM review_log WHERE user_id = #{userId}")
    long countReviews(@Param("userId") long userId);
}
