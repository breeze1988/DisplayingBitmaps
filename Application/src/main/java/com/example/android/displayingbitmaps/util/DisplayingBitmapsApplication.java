package com.example.android.displayingbitmaps.util;

import android.app.Application;

/**
 * Created by Administrator on 2016/1/7.
 */
public class DisplayingBitmapsApplication extends Application {

    private static DisplayingBitmapsApplication mInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        mInstance = this;
//
////        if (BuildConfig.DEBUG) {
//            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll()
//                    .penaltyLog().penaltyDropBox()
//                    .build());
//            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectAll().penaltyLog()
//                    .penaltyDropBox()
//                    .build());
        }
//    }

    public static DisplayingBitmapsApplication getmInstance(){return mInstance;}
}
