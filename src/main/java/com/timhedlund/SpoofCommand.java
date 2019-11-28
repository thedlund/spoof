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

import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.net.util.SubnetUtils;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(mixinStandardHelpOptions = true)
public class SpoofCommand implements Runnable {

  @Option(
      names = {"-H", "--host"},
      description = "The host header")
  private String host;

  @Option(
      names = {"-c", "--connection-timout"},
      description =
          "Sets a specified timeout value, in milliseconds, to be used when opening a communications link",
      defaultValue = "5000")
  private int connectTimeout;

  @Option(
      names = {"-r", "--read-timout"},
      description = "Sets the read timeout to a specified timeout, inmilliseconds.",
      defaultValue = "5000")
  private int readTimeout;

  @Option(
      names = {"-f", "--follow-redirects"},
      description =
          "Sets whether HTTP redirects (requests with response code 3xx) should be automatically followed",
      defaultValue = "true")
  private boolean followRedirects;

  @Option(
      names = {"--ip-range"},
      description = "Sets an ip range with CIDR notation which will be spoofed with.")
  private String cidr;

  @Option(
      names = {"--max-threads"},
      description =
          "Sets the max threads that will be used concurrently. This option is only available when using --ip-range.",
      defaultValue = "30")
  private int threads;

  @Parameters(index = "0", description = "The URL to check. E.g https://192.168.0.1/monkey")
  private String urlToCheck;

  public static void main(String[] args) {
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    new CommandLine(new SpoofCommand()).execute(args);
  }

  @Override
  public void run() {

    System.out.printf("Will check the URL %s with host header \"Host: %s\"\n", urlToCheck, host);

    // Parse URL
    final URL url = parseUrl(urlToCheck);

    // Range check
    if (cidr != null) {

      SubnetUtils utils = new SubnetUtils(cidr);
      String[] addresses = utils.getInfo().getAllAddresses();

      System.out.printf("Range check: %s - %s\n", addresses[0], addresses[addresses.length - 1]);

      List<String> addressList = new ArrayList<>(Arrays.asList(addresses));

      ExecutorService executor = Executors.newFixedThreadPool(threads);
      List<Future<String>> futures = new ArrayList<>();

      for (String address : addressList) {
        Future<String> future =
            executor.submit(
                () -> {
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

  private URL replaceHostInUrl(URL originalUrl, String newHost) {
    URL url = null;
    try {
      url = new URL(originalUrl.getProtocol(), newHost, originalUrl.getFile());
    } catch (MalformedURLException e) {
      e.printStackTrace();
      System.exit(1);
    }
    return url;
  }

  private URL parseUrl(String url) {
    URL parsedUrl = null;
    try {
      parsedUrl = new URL(url);
    } catch (MalformedURLException e) {
      e.printStackTrace();
      System.exit(1);
    }
    return parsedUrl;
  }

  private String doGet(URL url) {

    HttpURLConnection con = null;
    StringBuilder response = new StringBuilder();

    try {

      con = (HttpURLConnection) url.openConnection();
      con.setRequestMethod("GET");

      if (host != null) {
        con.setRequestProperty("Host", host);
      }

      con.setInstanceFollowRedirects(followRedirects);
      con.setConnectTimeout(connectTimeout);
      con.setReadTimeout(readTimeout);

      int responseCode = con.getResponseCode();
      response.append(responseCode + " ");

    } catch (SocketException se) {
      response.append(se.getMessage());
    } catch (SocketTimeoutException ste) {
      // Consuela - it's okay.
    } catch (IOException e) {
      response.append(e.getMessage());
    } finally {
      if (con != null) {
        con.disconnect();
      }
    }

    // Insert host if we got a meaningful response.
    if (response.length() != 0) {
      response.insert(0, url.getHost() + " : ");

      return response.toString();
    }

    return null;
  }
}
