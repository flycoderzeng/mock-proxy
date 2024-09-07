package com.tm.proxy;


import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    public static final String OPTION_TARGET_IP = "target-ip";
    public static final String OPTION_TARGET_PORT = "target-port";
    public static final String OPTION_TARGET_PROTOCOL = "target-protocol";
    public static final String OPTION_USERNAME = "username";
    public static final String OPTION_PASSWORD = "password";
    public static final String PROXY_COMMAND_LINE_USAGE = "proxy command line usage";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String PROTOCOL_HTTPS = "https";
    private static int port;
    private static String targetIP;
    private static String targetPort;
    private static String targetProtocol;
    private static String username;
    private static String password;
    private static String jksPath;
    private static String keyPasswd;

    public static final String PROXY_API_HELP = "\r\n\r\n====代理的接口如下====\r\n设置mock规则\r\n" + "curl --location 'https://127.0.0.1:8443/__proxyApi/setMockRule' \\\n" +
            "--header 'Content-Type: application/json' \\\n" +
            "--data '{\"targetProtocol\": \"https\", \"targetIP\":\"dev.kt.looklook.cn\", \"targetPort\":443, \"mockUri\": \"/getDataNodeList\", \"mockResponseStatusCode\":200, \"mockResponseBody\": \"mock返回的,hello,world\"}'\r\n\r\n清空mock规则\r\ncurl --location 'https://127.0.0.1:8443/__proxyApi/clearMockRule'\r\n\r\n删除mock规则\r\ncurl --location 'https://127.0.0.1:8443/__proxyApi/deleteMockRule' \\\n" +
            "--header 'Content-Type: application/json' \\\n" +
            "--data '{\"targetProtocol\": \"https\", \"targetIP\":\"dev.kt.looklook.cn\", \"targetPort\":443, \"mockUri\": \"/getDataNodeList\", \"mockResponseStatusCode\":200, \"mockResponseBody\": \"mock返回的\"}'\r\n\r\n获取所有mock规则\r\ncurl --location 'https://127.0.0.1:8443/__proxyApi/getAllMockRule'\r\n\r\n";

    private static final Map<String, MockRule> MOCK_RULE_MAP = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        final Options options = getOptions();
        parseOptions(args, options);
        final Server server = getServer(port);

        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String apiPath, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                if (StringUtils.isNoneBlank(apiPath) && apiPath.startsWith("/__proxyApi/")) {
                    try {
                        handleProxyApi(apiPath, baseRequest, request, response);
                    } catch (Exception e) {
                        e.printStackTrace();
                        returnBasicResponse(response, HttpStatus.INTERNAL_SERVER_ERROR_500, BaseResponse.baseFail("Error: " + e.getMessage()), baseRequest);
                    }
                    return;
                }
                String targetUrl = targetProtocol + "://" + targetIP + ":" + targetPort + apiPath;
                if (MOCK_RULE_MAP.containsKey(targetUrl) && MOCK_RULE_MAP.get(targetUrl) != null && MOCK_RULE_MAP.get(targetUrl).getMaxWorkNum().get() > 0) {
                    try {
                        mockReturn(baseRequest, response, targetUrl);
                    } catch (Exception e) {
                        e.printStackTrace();
                        returnBasicResponse(response, HttpStatus.INTERNAL_SERVER_ERROR_500, BaseResponse.baseFail("Error: " + e.getMessage()), baseRequest);
                    } finally {
                        final MockRule mockRule = MOCK_RULE_MAP.get(targetUrl);
                        if(mockRule != null) {
                            mockRule.getMaxWorkNum().getAndDecrement();
                        }
                    }
                    return;
                }

                HttpClient httpClient;
                if (StringUtils.equals(targetProtocol, PROTOCOL_HTTPS)) {
                    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
                    sslContextFactory.setTrustAll(true);
                    sslContextFactory.setValidateCerts(false);
                    httpClient = new HttpClient(sslContextFactory);
                } else {
                    httpClient = new HttpClient();
                }

                reverseProxy(baseRequest, request, response, httpClient, targetUrl);
            }
        });

        server.start();
        server.join();
    }

    private static void reverseProxy(Request baseRequest, HttpServletRequest request, HttpServletResponse response, HttpClient httpClient, String targetUrl) throws IOException {
        try {
            httpClient.start();
            if (StringUtils.isNoneBlank(baseRequest.getQueryString())) {
                targetUrl += "?" + baseRequest.getQueryString();
            }
            System.out.println("Target URL: " + targetUrl);
            final org.eclipse.jetty.client.api.Request targetRequest = httpClient.newRequest(targetUrl)
                    .method(HttpMethod.valueOf(request.getMethod()));
            final Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (StringUtils.equals(headerName, CONTENT_TYPE) || StringUtils.equals(headerName, "Authorization")) {
                    targetRequest.header(headerName, request.getHeader(headerName));
                }
            }
            StringBuilder content = getRequestBody(request);
            if(StringUtils.isNoneBlank(content)) {
                System.out.println("Request Content: " + content);
                targetRequest.content(new StringContentProvider(content.toString()));
            }
            final ContentResponse contentResponse = targetRequest.send();

            response.setStatus(contentResponse.getStatus());
            for (String headerName : contentResponse.getHeaders().getFieldNamesCollection()) {
                response.addHeader(headerName, contentResponse.getHeaders().get(headerName));
            }

            String contentAsString = contentResponse.getContentAsString();
            System.out.println("Response Content: " + contentAsString);
            response.getWriter().print(contentAsString);
            baseRequest.setHandled(true);
        } catch (Exception e) {
            e.printStackTrace();
            returnBasicResponse(response, HttpStatus.INTERNAL_SERVER_ERROR_500, BaseResponse.baseFail("Error: " + e.getMessage()), baseRequest);
        } finally {
            try {
                httpClient.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void mockReturn(Request baseRequest, HttpServletResponse response, String targetUrl) throws IOException {
        MockRule rule = MOCK_RULE_MAP.get(targetUrl);
        response.setStatus(rule.getMockResponseStatusCode());
        if (rule.getMockResponseBody().startsWith("{")) {
            response.addHeader(CONTENT_TYPE, "application/json; charset=utf-8");
        } else if (rule.getMockResponseBody().contains("xml")) {
            response.addHeader(CONTENT_TYPE, "application/xml; charset=utf-8");
        } else {
            response.addHeader(CONTENT_TYPE, "text/plain; charset=utf-8");
        }
        response.getWriter().print(rule.getMockResponseBody());
        baseRequest.setHandled(true);
    }

    private static void handleProxyApi(String apiPath, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws Exception {
        System.out.println("调用代理接口: " + apiPath);
        StringBuilder content = getRequestBody(request);

        System.out.println("配置mock规则请求体内容：" + content);
        switch (apiPath) {
            case "/__proxyApi/setMockRule":
                System.out.println("设置mock规则");
                setMockRule(baseRequest, response, content);
                break;
            case "/__proxyApi/clearMockRule":
                System.out.println("清空mock规则");
                MOCK_RULE_MAP.clear();
                returnBasicResponse(response, HttpStatus.OK_200, BaseResponse.baseSuccess("清空mock规则成功"), baseRequest);
                break;
            case "/__proxyApi/deleteMockRule":
                System.out.println("删除mock规则");
                deleteMockRule(baseRequest, response, content);
                break;
            case "/__proxyApi/getAllMockRule":
                System.out.println("获取所有mock规则");
                returnBasicResponse(response, HttpStatus.OK_200, BaseResponse.baseSuccess(MOCK_RULE_MAP), baseRequest);
                break;
            default:
                System.out.println("非法的接口");
                returnBasicResponse(response, HttpStatus.INTERNAL_SERVER_ERROR_500, BaseResponse.baseFail("invalid api"), baseRequest);
                break;
        }
    }

    private static StringBuilder getRequestBody(HttpServletRequest request) throws IOException {
        InputStream inputStream = request.getInputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        StringBuilder content = new StringBuilder();
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            content.append(new String(buffer, 0, bytesRead));
        }
        inputStream.close();
        return content;
    }

    private static void deleteMockRule(Request baseRequest, HttpServletResponse response, StringBuilder content) throws Exception {
        final MockRule mockRule = getMockRule(content);
        final String ruleKey = mockRule.getTargetProtocol() + "://" + mockRule.getTargetIP() + ":" + mockRule.getTargetPort() + mockRule.getMockUri();
        System.out.println("ruleKey: " + ruleKey);
        MOCK_RULE_MAP.remove(ruleKey);
        returnBasicResponse(response, HttpStatus.OK_200, BaseResponse.baseSuccess("删除mock规则成功"), baseRequest);
    }

    private static MockRule getMockRule(StringBuilder content) throws Exception {
        final MockRule mockRule = JSONObject.parseObject(content.toString(), MockRule.class);
        if (mockRule == null) {
            throw new Exception("请求体错误");
        }
        if (StringUtils.isBlank(mockRule.getTargetIP())) {
            throw new Exception("目标IP或域名不能为空");
        }
        if (StringUtils.isBlank(mockRule.getMockUri())) {
            throw new Exception("目标接口路径不能为空");
        }
        if (StringUtils.equals(mockRule.getTargetProtocol(), PROTOCOL_HTTPS) && mockRule.getTargetPort() <= 0) {
            mockRule.setTargetPort(443);
        }
        if (StringUtils.equals(mockRule.getTargetProtocol(), "http") && mockRule.getTargetPort() <= 0) {
            mockRule.setTargetPort(80);
        }
        return mockRule;
    }

    private static void setMockRule(Request baseRequest, HttpServletResponse response, StringBuilder content) throws Exception {
        final MockRule mockRule = getMockRule(content);
        final String ruleKey = mockRule.getTargetProtocol() + "://" + mockRule.getTargetIP() + ":" + mockRule.getTargetPort() + mockRule.getMockUri();
        System.out.println("ruleKey: " + ruleKey);
        MOCK_RULE_MAP.put(ruleKey, mockRule);
        returnBasicResponse(response, HttpStatus.OK_200, BaseResponse.baseSuccess("设置mock规则成功"), baseRequest);
    }

    private static void returnBasicResponse(HttpServletResponse response, int statusCode, String message, Request baseRequest) throws IOException {
        response.setStatus(statusCode);
        if (message != null && message.startsWith("{")) {
            response.addHeader(CONTENT_TYPE, "application/json; charset=utf-8");
        }else {
            response.addHeader(CONTENT_TYPE, "text/plain; charset=utf-8");
        }
        response.getWriter().print(message);
        baseRequest.setHandled(true);
    }

    private static void parseOptions(String[] args, Options options) {
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("port")) {
                port = Integer.parseInt(cmd.getOptionValue("port"));
            }
            if (cmd.hasOption(OPTION_TARGET_IP)) {
                targetIP = cmd.getOptionValue(OPTION_TARGET_IP);
            }
            if (cmd.hasOption(OPTION_TARGET_PORT)) {
                targetPort = cmd.getOptionValue(OPTION_TARGET_PORT);
            }
            if (cmd.hasOption(OPTION_TARGET_PROTOCOL)) {
                targetProtocol = cmd.getOptionValue(OPTION_TARGET_PROTOCOL);
            }
            if (StringUtils.isBlank(targetProtocol)) {
                targetProtocol = "http";
            }
            if (cmd.hasOption(OPTION_USERNAME)) {
                username = cmd.getOptionValue(OPTION_USERNAME);
            }
            if (cmd.hasOption(OPTION_PASSWORD)) {
                password = cmd.getOptionValue(OPTION_PASSWORD);
            }
            if (cmd.hasOption("jks")) {
                jksPath = cmd.getOptionValue("jks");
            }
            if (cmd.hasOption("key-password")) {
                keyPasswd = cmd.getOptionValue("key-password");
            }
            if (StringUtils.isBlank(keyPasswd)) {
                keyPasswd = "12345678";
            }
            if (targetPort == null && StringUtils.equals(targetProtocol, "http")) {
                targetPort = "80";
            }
            if (targetPort == null && StringUtils.equals(targetProtocol, PROTOCOL_HTTPS)) {
                targetPort = "443";
            }
        } catch (ParseException e) {
            System.out.println("Error parsing command line arguments: " + e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(PROXY_COMMAND_LINE_USAGE, options);
            System.out.println(PROXY_API_HELP);
            System.exit(1);
        }
        if (port <= 0) {
            port = 18097;
        }
        if (targetIP == null) {
            System.out.println("Please specify a valid IP or domain address");
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(PROXY_COMMAND_LINE_USAGE, options);
            System.out.println(PROXY_API_HELP);
            System.exit(1);
        }
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption("p", "port", true, "Specify the listen port.");
        options.addOption("tip", OPTION_TARGET_IP, true, "Specify the target http service ip or domain.");
        options.addOption("tport", OPTION_TARGET_PORT, true, "Specify the target http service port, if http default as 80, if https default as 443.");
        options.addOption("protocol", OPTION_TARGET_PROTOCOL, true, "Specify the target http service protocol, default: http.");
        options.addOption("U", OPTION_USERNAME, true, "Specify the username.");
        options.addOption("P", OPTION_PASSWORD, true, "Specify the password.");
        options.addOption("jks", "jks", true, "Specify the jks file path.");
        options.addOption("kpwd", "key-password", true, "Specify the jks file password, default: 12345678.");
        return options;
    }

    public static Server getServer(int port) {
        Server server = new Server();
        // 设置 Jetty 的 HTTP 配置，这里可以配置 HTTPS 的相关设置（如果需要）
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        ServerConnector connector;
        if(StringUtils.equals(targetProtocol, PROTOCOL_HTTPS)) {
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(jksPath);
            sslContextFactory.setKeyStorePassword(keyPasswd);
            connector = new ServerConnector(server, sslContextFactory, new HttpConnectionFactory(httpConfiguration));
        }else{
            connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
        }
        connector.setPort(port);
        server.addConnector(connector);

        return server;
    }
}