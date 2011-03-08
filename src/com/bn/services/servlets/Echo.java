package com.bn.services.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet implementation class Echo
 */
public class Echo extends HttpServlet {
	private static final long serialVersionUID = 1L;
       
    /**
     * @see HttpServlet#HttpServlet()
     */
    public Echo() {
        super();
        // TODO Auto-generated constructor stub
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doRequest(request, response);
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doRequest(request, response);
	}

	/**
	 * @see HttpServlet#doPut(HttpServletRequest, HttpServletResponse)
	 */
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doRequest(request, response);
	}

	/**
	 * @see HttpServlet#doDelete(HttpServletRequest, HttpServletResponse)
	 */
	protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doRequest(request, response);
	}

	/**
	 * @see HttpServlet#doHead(HttpServletRequest, HttpServletResponse)
	 */
	protected void doHead(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		// TODO Auto-generated method stub
		doRequest(request, response);
	}

	protected void doRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		String contextRoot = request.getContextPath();
//		String sourcePath = request.getRequestURI().substring(contextRoot.length() + "/Echo".length());
		String sourcePath = request.getRequestURI().substring(contextRoot.length());
		try {
			response.setContentType("text/plain");
			response.getOutputStream().println("Requested path: " + sourcePath + ((request.getQueryString() != null) ? ("?" + request.getQueryString()) : ""));
			response.getOutputStream().println("-------- Headers --------");
			@SuppressWarnings("rawtypes")
			Enumeration headerNames = request.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				String headerName = (String)headerNames.nextElement();
				String headerValue = request.getHeader(headerName);
				response.getOutputStream().println(headerName + ": " + headerValue);
			}
			response.getOutputStream().println("-------- Body --------");
			if (request.getContentLength() > 0) {
				copyStream(
						(InputStream)request.getInputStream(), (OutputStream)response.getOutputStream()
						);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void copyStream (InputStream in, OutputStream out) {
		copyStream(in, out, 4096);
	}

	private void copyStream (InputStream in, OutputStream out, int bufferSize) {
		int readLen = 0;
		byte[] copyBuffer = new byte[bufferSize];

		try {
			while ((readLen = in.read(copyBuffer)) > 0) {
				out.write(copyBuffer, 0, readLen);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

//	private void echo (String msg) {
//		System.out.println("[EchoServlet] " + msg);
//	}

}