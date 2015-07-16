package com.samsung.cassandra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.X509Certificate;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.map.ObjectMapper;
import org.apache.cassandra.locator.SeedProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KubernetesSeedProvider implements SeedProvider {
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Address {
        public String IP;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Subset {
        public List<Address> addresses; 
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Endpoints {
        public List<Subset> subsets;
    }
    
    private static String getEnvOrDefault(String var, String def) {
        String val = System.getenv(var);
        if (val == null) {
	    val = def;
        }
        return val;
    }

    private static String getServiceAccountToken() throws IOException {
        String file = "/var/run/secrets/kubernetes.io/serviceaccount/token";
        return new String(Files.readAllBytes(Paths.get(file)));
    }

    private static final Logger logger = LoggerFactory.getLogger(KubernetesSeedProvider.class);

    private List defaultSeeds;
    private TrustManager[] trustAll;
    private HostnameVerifier trustAllHosts;

    public KubernetesSeedProvider(Map<String, String> params) {
        // Taken from SimpleSeedProvider.java
        // These are used as a fallback, if we get nothing from this seed.
        String[] hosts = params.get("seeds").split(",", -1);
        defaultSeeds = new ArrayList<InetAddress>(hosts.length);
        for (String host : hosts)
	    {
		try {
		    defaultSeeds.add(InetAddress.getByName(host.trim()));
		}
		catch (UnknownHostException ex)
		    {
			// not fatal... DD will bark if there end up being zero seeds.
			logger.warn("Seed provider couldn't lookup host " + host);
		    }
	    }
	// TODO: Load the CA cert when it is available on all platforms.
	trustAll = new TrustManager[] {
	    new X509TrustManager() {
		public void checkServerTrusted(X509Certificate[] certs, String authType) {}
		public void checkClientTrusted(X509Certificate[] certs, String authType) {}
		public X509Certificate[] getAcceptedIssuers() { return null; }
	    }
	};
	trustAllHosts = new HostnameVerifier() {
		public boolean verify(String hostname, SSLSession session) {
		    return true;
		}
	    };
    }

    public List<InetAddress> getSeeds() {
        List<InetAddress> list = new ArrayList<InetAddress>();
	//
	// Don't specify domain as it may be differnet per sintall
	// e.g. we user kubernetes.local but exapple used cluster.local
	//
        String host = "https://kubernetes.default";
        String serviceName = getEnvOrDefault("CASSANDRA_SERVICE", "cassandra");
        String path = "/api/v1/namespaces/default/endpoints/";
        try {
            //String token = getServiceAccountToken();

            SSLContext ctx = SSLContext.getInstance("SSL");
            ctx.init(null, trustAll, new SecureRandom());

            URL url = new URL(host + path + serviceName);
            HttpsURLConnection conn = (HttpsURLConnection)url.openConnection();

            // TODO: Remove this once the CA cert is propogated everywhere, and replace
            // with loading the CA cert.
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier(trustAllHosts);

            //conn.addRequestProperty("Authorization", "Bearer " + token);
            ObjectMapper mapper = new ObjectMapper();
            Endpoints endpoints = mapper.readValue(conn.getInputStream(), Endpoints.class);
            if (endpoints != null) {
                // Here is a problem point, endpoints.subsets can be null in first node cases.
                if (endpoints.subsets != null && !endpoints.subsets.isEmpty()){
                    for (Subset subset : endpoints.subsets) {
                        for (Address address : subset.addresses) {
                            list.add(InetAddress.getByName(address.IP));
                        }
                    }
                }
            }
        } catch (IOException ex) {
	    logger.warn("Request to kubernetes apiserver failed: "+ ex); 
	} catch (NoSuchAlgorithmException ex) {
	    logger.warn("Request to kubernetes apiserver failed: "+ ex); 
	} catch (KeyManagementException ex) {
	    logger.warn("Request to kubernetes apiserver failed: "+ ex); 
        }
        if (list.size() == 0) {
	    // If we got nothing, we might be the first instance, in that case
	    // fall back on the seeds that were passed in cassandra.yaml.
	    return defaultSeeds;
        }
	logger.info("Kubernetes getSeeds will return: "+list);
        return list;
    }

    // Simple main to test the implementation
    public static void main(String[] args) {
        SeedProvider provider = new KubernetesSeedProvider(new HashMap<String, String>());
        System.out.println(provider.getSeeds());
    }
}
