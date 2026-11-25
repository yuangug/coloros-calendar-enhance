package com.lcodexs.coursealarm;

import com.astraisland.sdk.ProgressCard;
import java.util.List;

/** Standalone Android runtime probe. Builds actual SDK cards without posting an activity. */
public final class AstraStageProbe {
    public static void main(String[] args) {
        if (android.os.Looper.getMainLooper()==null) android.os.Looper.prepareMainLooper();
        long start=1_800_000_000_000L, minute=60000, end=start+100*minute;
        CourseBreaks breaks=CourseBreaks.decode(start,end,new long[]{start+30*minute,start+40*minute,4});
        ProgressCard.Builder staged=new ProgressCard.Builder("测试连堂课");
        CourseAstraBroker.configureProgress(staged,breaks,start,end,start+35*minute,"08:00","09:40");
        ProgressCard result=staged.build();
        if (!result.getStages().equals(List.of("08:00","课间","09:40")) || result.getStartLabel()!=null
                || result.getEndLabel()!=null || result.getFraction()!=0.5f) throw new AssertionError("staged card");
        ProgressCard.Builder plain=new ProgressCard.Builder("测试单节课");
        CourseAstraBroker.configureProgress(plain,CourseBreaks.NONE,start,end,start+35*minute,"08:00","09:40");
        ProgressCard ordinary=plain.build();
        if (!ordinary.getStages().isEmpty() || !"08:00".equals(ordinary.getStartLabel())
                || !"09:40".equals(ordinary.getEndLabel()) || Math.abs(ordinary.getFraction()-0.35f)>1e-6f)
            throw new AssertionError("single lesson");
        System.out.println("SDK PASS: 08:00 / 课间 / 09:40, break midpoint 50%; ordinary card 35% with endpoint labels");
    }
}
