package logger;

public class LoggerMain {
    /*
     * {3} started at {7} and ended at {19}
     * {2} started at {8} and ended at {12}
     * {1} started at {12} and ended at {15}
     */
    public static void main(String[] args) {
        final LogClient logger = new GoogleLogClient();
        logger.start("1", 1);
        logger.poll();
        logger.start("3", 2);
        logger.poll();
        logger.end("1");
        logger.poll();
        logger.start("2", 3);
        logger.poll();
        logger.end("2");
        logger.poll();
        logger.end("3");
        logger.poll();
        logger.poll();
        logger.poll();
        //1
        //3
        //2
    }
}
