package com.nauty.launcher3d;

import android.view.MotionEvent;
import android.view.Surface;

/** shell 권한 도우미(HelperService)의 인터페이스. Shizuku UserService 로 뜬다. */
interface IHelper {
    /** Shizuku 가 서비스를 내릴 때 부른다. 번호는 Shizuku 가 정한 것. */
    void destroy() = 16777114;

    /** surface 를 화면으로 쓰는 신뢰된 가상 디스플레이를 만든다. 디스플레이 번호를 돌려준다. */
    int createDisplay(in Surface surface, int w, int h, int dpi) = 1;

    /** 디스플레이를 없앤다. 그 위의 앱 화면도 함께 닫힌다. */
    void releaseDisplay(int displayId) = 2;

    /** 앱을 강제 종료한 뒤 그 디스플레이에서 새로 실행한다. 실패하면 오류 문구, 성공하면 null. */
    String launch(String component, int displayId) = 3;

    /** 디스플레이 좌표로 바꾼 터치를 주입한다. */
    oneway void injectMotion(in MotionEvent event, int displayId) = 4;

    /** 키 누름+뗌을 주입한다 (뒤로 가기 등). */
    oneway void injectKey(int keyCode, int displayId) = 5;

    /** 디스플레이에 남은 태스크 수. 알 수 없으면 -1. */
    int taskCount(int displayId) = 6;
}
