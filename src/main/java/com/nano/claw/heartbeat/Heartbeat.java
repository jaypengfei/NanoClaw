package com.nano.claw.heartbeat;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Jason
 * @description 高并发高计算量心跳检测 - 多线程并行计算，最大化CPU利用率
 * @date 2026/5/23
 */
public class Heartbeat {

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final int THREAD_COUNT = CPU_CORES * 2;
    private static final AtomicLong TOTAL_COMPUTATIONS = new AtomicLong(0);
    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        System.out.println("  NanoClaw Heartbeat - 高并发计算压力测试");
        System.out.println("  CPU核心数: " + CPU_CORES);
        System.out.println("  工作线程数: " + THREAD_COUNT);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT, r -> {
            Thread t = new Thread(r, "heartbeat-worker-" + Thread.currentThread().getId());
            t.setPriority(Thread.MAX_PRIORITY);
            t.setDaemon(false);
            return t;
        });

        // 注册JVM关闭钩子，优雅停机
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            executor.shutdownNow();
            System.out.println("\n[Shutdown] 收到终止信号，正在停止...");
        }));

        // 阶段1: 密集矩阵运算
        List<Future<Long>> matrixFutures = submitMatrixTasks(executor);

        // 阶段2: 素数计算
        List<Future<Long>> primeFutures = submitPrimeTasks(executor);

        // 阶段3: 傅里叶变换模拟
        List<Future<Long>> fftFutures = submitFftTasks(executor);

        // 阶段4: 持续压力循环
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });

        monitor.scheduleAtFixedRate(() -> {
            long total = TOTAL_COMPUTATIONS.get();
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
            System.out.printf("[Monitor] 计算总量: %,d | 内存使用: %dMB | 线程数: %d%n",
                    total, usedMB, Thread.activeCount());
        }, 1, 2, TimeUnit.SECONDS);

        // 等待阶段1完成
        System.out.println("\n>>> 阶段1: 密集矩阵运算开始...");
        long matrixResult = aggregateResults(matrixFutures, "矩阵运算");

        // 等待阶段2完成
        System.out.println("\n>>> 阶段2: 素数计算开始...");
        long primeResult = aggregateResults(primeFutures, "素数计算");

        // 等待阶段3完成
        System.out.println("\n>>> 阶段3: 傅里叶变换模拟开始...");
        long fftResult = aggregateResults(fftFutures, "傅里叶变换");

        // 阶段5: 无限循环压力测试
        System.out.println("\n>>> 阶段4: 持续压力循环（Ctrl+C停止）...");
        List<Future<?>> infiniteFutures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int workerId = i;
            infiniteFutures.add(executor.submit(() -> infiniteStressTest(workerId)));
        }

        // 主线程等待
        for (Future<?> f : infiniteFutures) {
            try {
                f.get();
            } catch (CancellationException | InterruptedException ignored) {
                break;
            }
        }

        executor.shutdown();
        monitor.shutdown();
        System.out.println("\n[Done] 总计算量: " + TOTAL_COMPUTATIONS.get());
    }

    /**
     * 密集矩阵运算 - 多线程并行矩阵乘法
     */
    private static List<Future<Long>> submitMatrixTasks(ExecutorService executor) {
        List<Future<Long>> futures = new ArrayList<>();
        int size = 512;
        double[][] a = generateMatrix(size);
        double[][] b = generateMatrix(size);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int taskId = t;
            futures.add(executor.submit(() -> {
                long ops = 0;
                double[][] result = new double[size][size];
                int rowPerThread = size / THREAD_COUNT;
                int startRow = taskId * rowPerThread;
                int endRow = (taskId == THREAD_COUNT - 1) ? size : startRow + rowPerThread;

                for (int round = 0; round < 10; round++) {
                    for (int i = startRow; i < endRow; i++) {
                        for (int j = 0; j < size; j++) {
                            double sum = 0;
                            for (int k = 0; k < size; k++) {
                                sum += a[i][k] * b[k][j];
                            }
                            result[i][j] = sum;
                            ops += size;
                        }
                    }
                }
                TOTAL_COMPUTATIONS.addAndGet(ops);
                return ops;
            }));
        }
        return futures;
    }

    /**
     * 素数计算 - 埃拉托斯特尼筛法 + Miller-Rabin测试
     */
    private static List<Future<Long>> submitPrimeTasks(ExecutorService executor) {
        List<Future<Long>> futures = new ArrayList<>();
        for (int t = 0; t < THREAD_COUNT; t++) {
            final int taskId = t;
            futures.add(executor.submit(() -> {
                long primeCount = 0;
                long start = 1_000_000L * taskId + 2;
                long end = start + 2_000_000L;

                for (long n = start; n < end; n += 2) {
                    if (isPrime(n)) {
                        primeCount++;
                    }
                    TOTAL_COMPUTATIONS.incrementAndGet();
                }
                System.out.printf("  [Prime-%d] 发现 %d 个素数 in [%d, %d)%n", taskId, primeCount, start, end);
                return primeCount;
            }));
        }
        return futures;
    }

    /**
     * 傅里叶变换模拟 - DFT计算
     */
    private static List<Future<Long>> submitFftTasks(ExecutorService executor) {
        List<Future<Long>> futures = new ArrayList<>();
        int n = 2048;

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int taskId = t;
            futures.add(executor.submit(() -> {
                long ops = 0;
                double[] signal = new double[n];
                for (int i = 0; i < n; i++) {
                    signal[i] = Math.sin(2 * Math.PI * i / n) + 0.5 * Math.sin(4 * Math.PI * i / n);
                }

                for (int round = 0; round < 50; round++) {
                    double[] real = new double[n];
                    double[] imag = new double[n];
                    for (int k = 0; k < n; k++) {
                        for (int j = 0; j < n; j++) {
                            double angle = -2 * Math.PI * k * j / n;
                            real[k] += signal[j] * Math.cos(angle);
                            imag[k] += signal[j] * Math.sin(angle);
                            ops += 4;
                        }
                    }
                }
                TOTAL_COMPUTATIONS.addAndGet(ops);
                return ops;
            }));
        }
        return futures;
    }

    /**
     * 无限循环压力测试 - 混合计算
     */
    private static void infiniteStressTest(int workerId) {
        System.out.printf("  [Stress-%d] 启动无限压力测试%n", workerId);
        long iteration = 0;
        while (running) {
            // 混合计算: 矩阵 + 三角函数 + 位运算
            double result = 0;
            for (int i = 0; i < 100_000; i++) {
                result += Math.sqrt(i) * Math.sin(i) * Math.cos(i);
                result = (long) result >> 3;
                result += Integer.bitCount(i) * Math.log1p(i);
            }
            iteration++;
            TOTAL_COMPUTATIONS.addAndGet(100_000);
            if (iteration % 100 == 0) {
                System.out.printf("  [Stress-%d] 完成第 %d 轮, 校验值=%.2f%n", workerId, iteration, result);
            }
        }
        System.out.printf("  [Stress-%d] 已停止, 共 %d 轮%n", workerId, iteration);
    }

    // ============ 工具方法 ============

    private static double[][] generateMatrix(int size) {
        double[][] matrix = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                matrix[i][j] = Math.random() * 100;
            }
        }
        return matrix;
    }

    private static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n < 4) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        // Miller-Rabin 简化测试
        long d = n - 1;
        while (d % 2 == 0) d /= 2;
        for (long a : new long[]{2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37}) {
            if (a >= n) continue;
            if (!millerRabinTest(n, d, a)) return false;
        }
        return true;
    }

    private static boolean millerRabinTest(long n, long d, long a) {
        long x = modPow(a, d, n);
        if (x == 1 || x == n - 1) return true;
        while (d != n - 1) {
            x = (x * x) % n;
            d *= 2;
            if (x == 1) return false;
            if (x == n - 1) return true;
        }
        return false;
    }

    private static long modPow(long base, long exp, long mod) {
        long result = 1;
        base %= mod;
        while (exp > 0) {
            if ((exp & 1) == 1) result = (result * base) % mod;
            exp >>= 1;
            base = (base * base) % mod;
        }
        return result;
    }

    private static long aggregateResults(List<Future<Long>> futures, String phaseName) throws Exception {
        long total = 0;
        for (Future<Long> f : futures) {
            total += f.get();
        }
        System.out.printf("  [%s] 完成, 结果=%,d%n", phaseName, total);
        return total;
    }
}
