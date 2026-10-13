package tech.qunxu.framework.excel.core.util;

import cn.idev.excel.FastExcel;
import cn.idev.excel.read.listener.PageReadListener;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 工具类，基于 FastExcel。
 * <p>
 * 通过实体类上的 {@link cn.idev.excel.annotation.ExcelProperty} 注解
 * 定义表头与字段映射，实现导入、导出。
 */
public final class ExcelUtils {

    private ExcelUtils() {
    }

    /**
     * 将数据导出为 Excel 并写入 HTTP 响应（附件下载）
     *
     * @param response  HTTP 响应
     * @param filename  下载文件名（不含后缀）
     * @param sheetName 工作表名
     * @param head      表头实体类
     * @param data      数据列表
     */
    public static <T> void write(HttpServletResponse response, String filename, String sheetName,
                                 Class<T> head, List<T> data) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 使用 RFC 5987 编码，避免中文文件名乱码
        String encodedFilename = URLEncoder.encode(filename + ".xlsx", StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + encodedFilename);
        write(response.getOutputStream(), sheetName, head, data);
    }

    /**
     * 将数据写入输出流
     */
    public static <T> void write(OutputStream outputStream, String sheetName,
                                 Class<T> head, List<T> data) throws IOException {
        FastExcel.write(outputStream, head).sheet(sheetName).doWrite(data);
    }

    /**
     * 从输入流读取 Excel 数据
     *
     * @param inputStream Excel 输入流
     * @param head        表头实体类
     * @return 数据列表
     */
    public static <T> List<T> read(InputStream inputStream, Class<T> head) {
        List<T> result = new ArrayList<>();
        // 通过分页监听器收集读取结果
        PageReadListener<T> listener = new PageReadListener<>(result::addAll);
        FastExcel.read(inputStream, head, listener).sheet().doRead();
        return result;
    }

}
