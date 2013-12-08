package com.sentilabs.mail;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

public class RabbitmqToAwsSes {

    private static Logger logger = LogManager.getLogger(RabbitmqToAwsSes.class.getName());

    private static void printHelp(){
        System.out.println("Usage: ");
        System.out.println("java -Dlog4j.configurationFile=/path/to/log4jconf com.sentilabs.mail.RabbitmqToAwsSes" +
                "ses=/path/to/file/with/aws_ses_params rmq=/path/to/rabbitmq_connection_params");
        logger.error("Parameters not specified");
    }

    public static void main(String[] args) {
        if (args.length < 2){
            printHelp();
            System.exit(0);
        }
        String awsSesFileParamsPath = null;
        String rabbitmqFileParams = null;
        for (String arg: args){
            if (arg.startsWith("ses=")){
                awsSesFileParamsPath = arg.substring(4);
            }
            else if (arg.startsWith("rmq=")){
                rabbitmqFileParams = arg.substring(4);
            }
        }
        RabbitmqToAwsSesRunnable r = new RabbitmqToAwsSesRunnable();
        if (!r.init(awsSesFileParamsPath, rabbitmqFileParams)){
            System.out.println("Error while initializing context");
            logger.error("Error while initializing context");
            System.exit(0);
        }
        new Thread(r).start();
    }
}
