package cn.netdiscovery.example.zookeeper;

import cn.netdiscovery.core.curator.CuratorManager;

/**
 * Created by tony on 2019-05-25.
 */
public class TestCuratorManager {

    public static void main(String[] args) {

        CuratorManager curatorManager = new CuratorManager();
        curatorManager.start();
    }
}
