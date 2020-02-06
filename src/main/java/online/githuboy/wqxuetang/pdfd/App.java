package online.githuboy.wqxuetang.pdfd;

import cn.hutool.core.io.FileUtil;
import cn.hutool.setting.dialect.Props;
import lombok.extern.slf4j.Slf4j;
import online.githuboy.wqxuetang.pdfd.api.ApiUtils;
import online.githuboy.wqxuetang.pdfd.pojo.BookMetaInfo;
import online.githuboy.wqxuetang.pdfd.pojo.Catalog;
import online.githuboy.wqxuetang.pdfd.pojo.Config;
import online.githuboy.wqxuetang.pdfd.utils.PDFUtils;
import online.githuboy.wqxuetang.pdfd.utils.ThreadPoolUtils;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class App {

    public static void main(String[] args) throws IOException, InterruptedException, ParseException {

        Options options = new Options();
        options.addOption(Option.builder("b").hasArg(true).required().desc("The id of book").build());
        options.addOption(Option.builder("c").hasArg(true).desc("Config file path.").build());
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            String footer = "\nPlease report issues at https://github.com/SweetInk/wqxuetang-pdf-downloader";
            formatter.printHelp("java -jar pdfd.jar", "\n", options, footer, true);
            return;
        }
        String bookId = cmd.getOptionValue("b");
        File configFile;
        String configPath = cmd.getOptionValue("c");
        if (null == configPath) {
            String jarPath = App.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            File classFile = new File(jarPath);
            if (classFile.isDirectory()) {
                configFile = new File(classFile, Constants.DEFAULT_CONFIG_FILE);
            } else {
                configFile = new File(classFile.getParent(), Constants.DEFAULT_CONFIG_FILE);
            }
        } else {
            configFile = FileUtil.file(configPath);
        }
        Config config = null;
        if (!configFile.isDirectory() && configFile.exists()) {
            Props props = new Props(configFile);
            config = props.toBean(Config.class, "config");
            AppContext.setConfig(config);
            log.info("配置文件:{}加载成功:\n{}", configFile.getAbsolutePath(), config);
        } else {
            log.error("配置文件:{}不存在，请检查路径是否正确", configFile.getAbsolutePath());
            return;
        }
        CookieStore.COOKIE = config.getCookie();
        log.info("Fetch book info bookId:{} ", bookId);
        long start = System.currentTimeMillis();
        BookMetaInfo metaInfo = ApiUtils.getBookMetaInfo(bookId);
        // Expire at 5 minutes later;
        String k = ApiUtils.getBookKey(bookId);
        List<Catalog> catalogs = ApiUtils.getBookCatalog(bookId);
        log.info("meta:{}", metaInfo);
        log.info("k:{}", k);
        log.info("cate:{}", catalogs);
        int pages = metaInfo.getPages();
        Map<Integer, Integer> pageMap = new HashMap<>();
        for (int i = 1; i <= pages; i++) {
            pageMap.put(i, i);
        }
        File tempSaveDir = new File(config.getWorkPath(), bookId);
        if (!tempSaveDir.exists())
            tempSaveDir.mkdirs();
        // 预先清理无效的图片
        Arrays.stream(Objects.requireNonNull(tempSaveDir.listFiles())).forEach(file -> {
            long size = file.length();
            if (size <= Constants.IMG_INVALID_SIZE
                    || size == Constants.IMG_LOADING_SIZE) {
                FileUtil.del(file);
            }
        });
        //列出已经下载好的图片列表
        List<Integer> strings = FileUtil.listFileNames(tempSaveDir.getAbsolutePath()).stream().map(
                fileName -> Integer.parseInt(fileName.substring(0, fileName.lastIndexOf('.')))
        ).sorted().collect(Collectors.toList());
        //之前成功下载的图片将会跳过
        List<Integer> failedImageList = pageMap.entrySet().stream().filter(entry -> !strings.contains(entry.getKey())).map(Map.Entry::getValue).collect(Collectors.toList());
        AppContext.setBookKey(bookId, k);
        if (failedImageList.size() <= 0) {
            log.info("All image downloaded(total:{})", failedImageList.size());
            log.info("Ready for generate PDF");
            PDFUtils.gen(metaInfo, catalogs, config.getWorkPath());
            log.info("All finished take :{}s", (System.currentTimeMillis() - start) / 1000);
        } else {
            ThreadPoolUtils.init(config.getThreadCount());
            ThreadPoolUtils.getScheduledExecutorService().schedule(() -> {
                log.info("清理book:{} key", bookId);
                try {
                    String bookKey = ApiUtils.getBookKey(bookId);
                    AppContext.setBookKey(bookId, bookKey);
                    log.info("更新book key成功:{}", bookKey);
                } catch (Exception e) {
                    log.error("Cookie已失效，或者服务不可用 ,请退出程序重试,errorMessage:{}", e.getMessage());
                }
            }, 4, TimeUnit.MINUTES);
            ThreadPoolExecutor executor = ThreadPoolUtils.getExecutor();
            CountDownLatch latch = new CountDownLatch(failedImageList.size());
            log.info("Start download image");
            for (Integer page : failedImageList) {
                FetchBookImageTask task = new FetchBookImageTask(config.getWorkPath(), bookId, page, latch);
                executor.execute(task);
            }
            latch.await();
            long successCount = AppContext.getImageStatusMapping().entrySet().stream().filter(Map.Entry::getValue).count();
            if (successCount == failedImageList.size()) {
                log.info("All image downloaded");
                log.info("Ready for generate PDF");
                PDFUtils.gen(metaInfo, catalogs, config.getWorkPath());
                log.info("All finished take :{}s", (System.currentTimeMillis() - start) / 1000);

            } else {
                AppContext.getImageStatusMapping().entrySet().stream().filter(entry -> !entry.getValue()).map(Map.Entry::getKey).forEach(pageNumber -> {
                    log.info("图片:{}下载失败", pageNumber);
                    FileUtil.del(new File(tempSaveDir, pageNumber + Constants.JPG_SUFFIX));
                });
                log.info("请重新运行本程序下载");
            }
            ThreadPoolUtils.getScheduledExecutorService().shutdown();
            executor.shutdown();
        }
    }


}
