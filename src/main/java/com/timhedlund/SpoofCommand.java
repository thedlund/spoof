package com.timhedlund;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.net.util.SubnetUtils;

import me.tongfei.progressbar.ProgressBar;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true)
public class SpoofCommand implements Runnable {

	@Option(names = { "-H", "--host" }, description = "The host header")
	String mHost;

	@Option(names = { "-c",
			"--connection-timout" }, description = "Sets a specified timeout value, in milliseconds, to be used when opening a communications link", defaultValue = "5000")
	int mConnectTimeout;

	@Option(names = { "-r",
			"--read-timout" }, description = "Sets the read timeout to a specified timeout, inmilliseconds.", defaultValue = "5000")
	int mReadTimeout;

	@Option(names = { "-f",
			"--follow-redirects" }, description = "Sets whether HTTP redirects (requests with response code 3xx) should be automatically followed", defaultValue = "true")
	boolean mFollowRedirects;

	@Option(names = { "--ip-range" }, description = "Sets an ip range with CIDR notation which will be spoofed with.")
	String mCIDR;
	
	@Option(names = { "--max-threads" }, 
			description = "Sets the max threads that will be used concurrently. This option is only available when using --ip-range.", 
			defaultValue = "30")
	int mThreads;

	@Parameters(index = "0", description = "The URL to check. E.g https://192.168.0.1/monkey")
	private String mURL;

	public static void main(String[] args) {
		System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
		new CommandLine(new SpoofCommand()).execute(args);
	}

	@Override
	public void run() {

		System.out.printf("Will check the URL %s with host header \"Host: %s\"\n", mURL, mHost);

		// Parse URL
		final URL url = parseURL(mURL);

		// Range check
		if (mCIDR != null) {
			
			SubnetUtils utils = new SubnetUtils(mCIDR);
			String[] addresses = utils.getInfo().getAllAddresses();
			
			
			System.out.printf("Range check: %s - %s\n", addresses[0], addresses[addresses.length - 1] );
			
			List<String> addressList = new ArrayList<>(Arrays.asList(addresses));
			
			ExecutorService executor = Executors.newFixedThreadPool(mThreads);
			List<Future<String>> futures = new ArrayList<>();
			
			for (String address : addressList) {
				Future<String> future = executor.submit(() -> {
					URL newUrl = replaceHostInUrl(url, address);	
					return doGet(newUrl);
				});
				
				futures.add(future);
			}
			
			List<String> result = new ArrayList<>();
			for (Future<String> future : ProgressBar.wrap(futures, "Hitting")) {
				try {
					result.add(future.get());
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (ExecutionException e) {
					e.printStackTrace();
				}
			}
			
			result.stream().filter(s -> !"".equals(s)).forEach(s -> System.out.println(s));
			
			executor.shutdown();

		} else {
			System.out.println(doGet(url));
		}

	}

	public URL replaceHostInUrl(URL originalURL, String newHost) {
		URL url = null;
		try {
			url = new URL(originalURL.getProtocol(), newHost, originalURL.getFile());
		} catch (MalformedURLException e) {
			e.printStackTrace();
			System.exit(1);
		}
		return url;
	}

	private URL parseURL(String url) {
		URL parsedURL = null;
		try {
			parsedURL = new URL(url);
		} catch (MalformedURLException e) {
			e.printStackTrace();
			System.exit(1);
		}
		return parsedURL;
	}

	private String doGet(URL url) {
		
		HttpURLConnection con = null;
		StringBuilder response = new StringBuilder();
		
		try {
			
			con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod("GET");

			if (mHost != null) {
				con.setRequestProperty("Host", mHost);
			}

			con.setInstanceFollowRedirects(mFollowRedirects);
			con.setConnectTimeout(mConnectTimeout);
			con.setReadTimeout(mReadTimeout);

			int responseCode = con.getResponseCode();
			response.append(responseCode + " ");

		}
		catch (SocketException se) {
			response.append(se.getMessage());
		}
		catch (SocketTimeoutException ste) {
			// Consuela - it's okay.
		} 
		catch (IOException e) {
			response.append(e.getMessage());
		}
		finally {
			if (con != null) {
				con.disconnect();
			}
		}
		
		// Insert host if we got a meaningful response.
		if (response.length() != 0) {
			response.insert(0, url.getHost() + " : ");
		}
		
		return response.toString();
	}

}
