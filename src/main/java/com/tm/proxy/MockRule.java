package com.tm.proxy;

import lombok.Data;

@Data
public class MockRule {
    private String targetIP;
    private int targetPort;
    private String targetProtocol = "http";
    private String mockUri;
    private int mockResponseStatusCode = 200;
    private String mockResponseBody = "";
}
