package cn.netdiscovery.core;

import cn.netdiscovery.core.config.Configuration;
import cn.netdiscovery.core.config.Constant;
import cn.netdiscovery.core.domain.Request;
import cn.netdiscovery.core.domain.bean.SpiderBean;
import cn.netdiscovery.core.domain.bean.SpiderJobBean;
import cn.netdiscovery.core.domain.response.JobsResponse;
import cn.netdiscovery.core.domain.response.SpiderResponse;
import cn.netdiscovery.core.domain.response.SpiderStatusResponse;
import cn.netdiscovery.core.domain.response.SpidersResponse;
import cn.netdiscovery.core.quartz.ProxyPoolJob;
import cn.netdiscovery.core.quartz.QuartzManager;
import cn.netdiscovery.core.quartz.SpiderJob;
import cn.netdiscovery.core.queue.Queue;
import cn.netdiscovery.core.utils.BooleanUtils;
import cn.netdiscovery.core.utils.NumberUtils;
import cn.netdiscovery.core.utils.SerializableUtils;
import cn.netdiscovery.core.utils.UserAgent;
import cn.netdiscovery.core.vertx.VertxUtils;
import com.cv4j.proxy.ProxyManager;
import com.cv4j.proxy.ProxyPool;
import com.cv4j.proxy.domain.Proxy;
import com.safframework.tony.common.utils.IOUtils;
import com.safframework.tony.common.utils.Preconditions;
import io.reactivex.Flowable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static cn.netdiscovery.core.config.Constant.JOB_GROUP_NAME;
import static cn.netdiscovery.core.config.Constant.PROXY_POOL_JOB_NAME;
import static cn.netdiscovery.core.config.Constant.SPIDER_JOB_NAME;
import static cn.netdiscovery.core.config.Constant.TRIGGER_GROUP_NAME;
import static cn.netdiscovery.core.config.Constant.TRIGGER_NAME;

/**
 * 可以管理多个Spider的容器
 * Created by tony on 2018/1/2.
 */
@Slf4j
public class SpiderEngine {

    @Getter
    private Queue queue;

    private HttpServer server;

    private boolean useMonitor = false;

    private boolean useZk = false;

    private String zkStr;

    private RegisterConsumer registerConsumer;

    private int defaultHttpdPort = 8715; // SpiderEngine 默认的端口号

    private AtomicInteger count = new AtomicInteger(0);

    private Map<String, Spider> spiders = new ConcurrentHashMap<>();

    private Map<String, SpiderJobBean> jobs = new ConcurrentHashMap<>();

    private SpiderEngine() {

        this(null);
    }

    private SpiderEngine(Queue queue) {

        this.queue = queue;

        initSpiderEngine();
    }

    /**
     * 初始化爬虫引擎，加载ua列表
     */
    private void initSpiderEngine() {

        String[] uaList = Constant.uaList;

        if (Preconditions.isNotBlank(uaList)) {

            Arrays.asList(uaList)
                    .parallelStream()
                    .forEach(name -> {

                        InputStream input = null;

                        try {
                            input = this.getClass().getResourceAsStream(name);
                            String inputString = IOUtils.inputStream2String(input); // input 流无须关闭，inputStream2String()方法里已经做了关闭流的操作
                            if (Preconditions.isNotBlank(inputString)) {
                                String[] ss = inputString.split("\r\n");
                                if (ss.length > 0) {

                                    Arrays.asList(ss).forEach(ua -> UserAgent.uas.add(ua));
                                }
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });

            com.cv4j.proxy.config.Constant.setUas(UserAgent.uas); // 让代理池也能够共享ua
        }

        try {
            defaultHttpdPort = NumberUtils.toInt(Configuration.getConfig("spiderEngine.config.port"));
            useMonitor = BooleanUtils.toBoolean(Configuration.getConfig("spiderEngine.config.useMonitor"));
            zkStr = Configuration.getConfig("spiderEngine.config.zkStr");
            useZk = BooleanUtils.toBoolean(Configuration.getConfig("spiderEngine.config.useZk"));
        } catch (ClassCastException e) {
            defaultHttpdPort = 8715;
            useMonitor = false;
            useZk = false;
        }
    }

    public static SpiderEngine create() {

        return new SpiderEngine();
    }

    public static SpiderEngine create(Queue queue) {

        return new SpiderEngine(queue);
    }

    public SpiderEngine proxyList(List<Proxy> proxies) {

        ProxyPool.addProxyList(proxies);
        return this;
    }

    public SpiderEngine setUseMonitor(boolean useMonitor) {

        this.useMonitor = useMonitor;
        return this;
    }

    /**
     * 添加爬虫到SpiderEngine，由SpiderEngine来管理
     *
     * @param spider
     * @return
     */
    public SpiderEngine addSpider(Spider spider) {

        if (spider != null && !spiders.containsKey(spider.getName())) {

            spiders.put(spider.getName(), spider);
        }
        return this;
    }

    /**
     * 在SpiderEngine中创建一个爬虫，使用SpiderEngine的Queue
     *
     * @param name
     * @return Spider
     */
    public Spider createSpider(String name) {

        if (!spiders.containsKey(name)) {

            Spider spider = Spider.create(this.getQueue()).name(name);
            spiders.put(name, spider);
            return spider;
        }

        return null;
    }

    /**
     * 对各个爬虫的状态进行监测，并返回json格式。
     * 如果要使用此方法，须放在run()之前
     * 采用默认的端口号
     * @return
     */
    public SpiderEngine httpd() {

        return httpd(defaultHttpdPort);
    }

    /**
     * 对各个爬虫的状态进行监测，并返回json格式。
     * 如果要使用此方法，须放在run()之前
     *
     * @param port
     */
    public SpiderEngine httpd(int port) {

        server = VertxUtils.getVertx().createHttpServer();

        Router router = Router.router(VertxUtils.getVertx());
        router.route().handler(BodyHandler.create());

        if (Preconditions.isNotBlank(spiders)) {

            for (Map.Entry<String, Spider> entry : spiders.entrySet()) {

                final Spider spider = entry.getValue();

                router.route("/netdiscovery/spider/" + spider.getName()).handler(routingContext -> {

                    // 所有的请求都会调用这个处理器处理
                    HttpServerResponse response = routingContext.response();
                    response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON);

                    SpiderBean entity = new SpiderBean();
                    entity.setSpiderName(spider.getName());
                    entity.setSpiderStatus(spider.getSpiderStatus());
                    entity.setLeftRequestSize(spider.getQueue().getLeftRequests(spider.getName()));
                    entity.setTotalRequestSize(spider.getQueue().getTotalRequests(spider.getName()));
                    entity.setConsumedRequestSize(entity.getTotalRequestSize()-entity.getLeftRequestSize());
                    entity.setQueueType(spider.getQueue().getClass().getSimpleName());
                    entity.setDownloaderType(spider.getDownloader().getClass().getSimpleName());

                    SpiderResponse spiderResponse = new SpiderResponse();
                    spiderResponse.setCode(Constant.OK_STATUS_CODE);
                    spiderResponse.setMessage(Constant.SUCCESS);
                    spiderResponse.setData(entity);

                    // 写入响应并结束处理
                    response.end(SerializableUtils.toJson(spiderResponse));
                });

                router.post("/netdiscovery/spider/" + spider.getName() + "/status").handler(routingContext -> {

                    // 所有的请求都会调用这个处理器处理
                    HttpServerResponse response = routingContext.response();
                    response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON);

                    JsonObject json = routingContext.getBodyAsJson();

                    SpiderStatusResponse spiderStatusResponse = null;

                    if (json != null) {

                        int status = json.getInteger("status");

                        spiderStatusResponse = new SpiderStatusResponse();

                        switch (status) {

                            case Spider.SPIDER_STATUS_PAUSE: {
                                spider.pause();
                                spiderStatusResponse.setData(String.format("SpiderEngine pause Spider %s success", spider.getName()));
                                break;
                            }

                            case Spider.SPIDER_STATUS_RESUME: {
                                spider.resume();
                                spiderStatusResponse.setData(String.format("SpiderEngine resume Spider %s success", spider.getName()));
                                break;
                            }

                            case Spider.SPIDER_STATUS_STOPPED: {
                                spider.forceStop();
                                spiderStatusResponse.setData(String.format("SpiderEngine stop Spider %s success", spider.getName()));
                                break;
                            }

                            default:
                                break;
                        }
                    }

                    spiderStatusResponse.setCode(Constant.OK_STATUS_CODE);
                    spiderStatusResponse.setMessage(Constant.SUCCESS);

                    // 写入响应并结束处理
                    response.end(SerializableUtils.toJson(spiderStatusResponse));
                });
            }

            router.route("/netdiscovery/spiders/").handler(routingContext -> {

                // 所有的请求都会调用这个处理器处理
                HttpServerResponse response = routingContext.response();
                response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON);

                List<SpiderBean> list = new ArrayList<>();

                Spider spider = null;
                SpiderBean entity = null;

                for (Map.Entry<String, Spider> entry : spiders.entrySet()) {

                    spider = entry.getValue();

                    entity = new SpiderBean();
                    entity.setSpiderName(spider.getName());
                    entity.setSpiderStatus(spider.getSpiderStatus());
                    entity.setLeftRequestSize(spider.getQueue().getLeftRequests(spider.getName()));
                    entity.setTotalRequestSize(spider.getQueue().getTotalRequests(spider.getName()));
                    entity.setConsumedRequestSize(entity.getTotalRequestSize()-entity.getLeftRequestSize());
                    entity.setQueueType(spider.getQueue().getClass().getSimpleName());
                    entity.setDownloaderType(spider.getDownloader().getClass().getSimpleName());
                    list.add(entity);
                }

                SpidersResponse spidersResponse = new SpidersResponse();
                spidersResponse.setCode(Constant.OK_STATUS_CODE);
                spidersResponse.setMessage(Constant.SUCCESS);
                spidersResponse.setData(list);

                // 写入响应并结束处理
                response.end(SerializableUtils.toJson(spidersResponse));
            });

            router.route("/netdiscovery/jobs/").handler(routingContext -> {

                // 所有的请求都会调用这个处理器处理
                HttpServerResponse response = routingContext.response();
                response.putHeader(Constant.CONTENT_TYPE, Constant.CONTENT_TYPE_JSON);

                List<SpiderJobBean> list = new ArrayList<>();

                list.addAll(jobs.values());

                JobsResponse jobsResponse = new JobsResponse();
                jobsResponse.setCode(Constant.OK_STATUS_CODE);
                jobsResponse.setMessage(Constant.SUCCESS);
                jobsResponse.setData(list);

                // 写入响应并结束处理
                response.end(SerializableUtils.toJson(jobsResponse));
            });

            // 是否使用 agent
            if (useMonitor) {

                // The web server handler
                router.route().handler(StaticHandler.create().setCachingEnabled(false));

                // The proxy handler
                WebClient client = WebClient.create(VertxUtils.getVertx());

                InetAddress localhost = null;
                try {
                    localhost = InetAddress.getLocalHost();
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }

                HttpRequest<Buffer> get = client.get(8081, localhost.getHostAddress(), "/netdiscovery/dashboard/");
                router.get("/dashboard").handler(ctx -> {
                    get.send(ar -> {
                        if (ar.succeeded()) {
                            HttpResponse<Buffer> result = ar.result();
                            ctx.response()
                                    .setStatusCode(result.statusCode())
                                    .putHeader("Content-Type", "application/json")
                                    .end(result.body());
                        } else {
                            ctx.fail(ar.cause());
                        }
                    });
                });
            }
        }

        server.requestHandler(router::accept).listen(port);

        return this;
    }

    /**
     * 注册 Vert.x eventBus 的消费者
     * @param registerConsumer
     * @return
     */
    public SpiderEngine registerConsumers(RegisterConsumer registerConsumer) {

        this.registerConsumer = registerConsumer;
        return this;
    }

    /**
     * 关闭HttpServer
     */
    public void closeHttpServer() {

        if (server != null) {

            server.close();
        }
    }

    /**
     * 启动SpiderEngine中所有的spider，让每个爬虫并行运行起来。
     *
     */
    public void run() {

        if (Preconditions.isNotBlank(spiders)) {

            registerZK();

            if (registerConsumer!=null) {
                registerConsumer.process();
            }

            Flowable.fromIterable(spiders.values())
                    .parallel(spiders.values().size())
                    .runOn(Schedulers.io())
                    .map(new Function<Spider, Spider>() {

                        @Override
                        public Spider apply(Spider spider) throws Exception {

                            spider.run();

                            return spider;
                        }
                    })
                    .sequential()
                    .subscribe();

            Runtime.getRuntime().addShutdownHook(new Thread(()-> {
                log.info("stop all spiders");
                stopSpiders();
                QuartzManager.shutdownJobs();
            }));
        }
    }

    /**
     * 将当前 SpiderEngine 注册到 zookeeper 指定的目录 /netdiscovery 下
     */
    private void registerZK() {

        if (Preconditions.isNotBlank(zkStr) && useZk) {
            log.info("zkStr: {}", zkStr);

            RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000,3);
            CuratorFramework client = CuratorFrameworkFactory.newClient(zkStr, retryPolicy);
            client.start();
            try {
                String ipAddr = InetAddress.getLocalHost().getHostAddress() + "-" + defaultHttpdPort + "-" + System.currentTimeMillis();
                String nowSpiderEngineZNode = "/netdiscovery/" + ipAddr;
                client.create().withMode(CreateMode.EPHEMERAL).forPath(nowSpiderEngineZNode,nowSpiderEngineZNode.getBytes());
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 基于爬虫的名字，从SpiderEngine中获取爬虫
     *
     * @param name
     */
    public Spider getSpider(String name) {

        return spiders.get(name);
    }

    /**
     * 停止某个爬虫程序
     *
     * @param name
     */
    public void stopSpider(String name) {

        Spider spider = spiders.get(name);

        if (spider != null) {

            spider.stop();
        }
    }

    /**
     * 停止所有的爬虫程序
     */
    public void stopSpiders() {

        if (Preconditions.isNotBlank(spiders)) {

            spiders.forEach((s, spider) -> spider.stop());
        }
    }

    /**
     * 给 Spider 发起定时任务
     * @param spiderName
     * @param cron cron表达式
     * @param urls
     */
    public SpiderJobBean addSpiderJob(String spiderName, String cron, String... urls) {

        if (Preconditions.isNotBlank(urls) && spiders.get(spiderName)!=null) {

            Request[] requests = new Request[urls.length];

            for (int i=0;i<urls.length;i++) {

                requests[i] = new Request(urls[i],spiderName).checkDuplicate(false);
            }

            return  addSpiderJob(spiderName,cron,requests);
        }

        return null;
    }

    /**
     * 给 Spider 发起定时任务
     * @param spiderName
     * @param cron cron表达式
     * @param requests
     */
    public SpiderJobBean addSpiderJob(String spiderName, String cron, Request... requests) {

        Spider spider = spiders.get(spiderName);

        if (spider!=null){
            String jobName = SPIDER_JOB_NAME + count.incrementAndGet();

            SpiderJobBean jobBean = new SpiderJobBean();
            jobBean.setJobName(jobName);
            jobBean.setJobGroupName(JOB_GROUP_NAME);
            jobBean.setTriggerName(TRIGGER_NAME);
            jobBean.setTriggerGroupName(TRIGGER_GROUP_NAME);
            jobBean.setCron(cron);
            jobBean.setRequests(requests);

            Stream.of(requests)
                    .forEach(request -> {
                        request.checkDuplicate(false);
                    });

            jobs.put(jobName, jobBean);
            QuartzManager.addJob(jobBean, SpiderJob.class, cron, spider, requests);

            return jobBean;
        }

        return null;
    }

    /**
     * 给 ProxyPool 发起定时任务
     * @param proxyMap
     * @param cron cron表达式
     * @return
     */
    public void addProxyPoolJob(Map<String, Class> proxyMap, String cron) {

        String jobName = PROXY_POOL_JOB_NAME + count.incrementAndGet();

        QuartzManager.addJob(jobName, JOB_GROUP_NAME, TRIGGER_NAME, TRIGGER_GROUP_NAME, ProxyPoolJob.class, cron, proxyMap);
    }

    /**
     * 需要在启动 SpiderEngine 之前，启动 ProxyPool
     */
    public void startProxyPool(Map<String, Class> proxyMap) {

        if (proxyMap == null) return;

        ProxyPool.proxyMap = proxyMap;
        ProxyManager proxyManager = ProxyManager.get();
        proxyManager.start();
    }

    /**
     * 注册 Vert.x eventBus 的消费者
     */
    @FunctionalInterface
    public interface RegisterConsumer {

        void process();
    }
}