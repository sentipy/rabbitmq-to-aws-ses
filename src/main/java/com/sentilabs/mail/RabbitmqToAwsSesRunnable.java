package com.sentilabs.mail;

import com.google.protobuf.InvalidProtocolBufferException;
import com.rabbitmq.client.*;
import com.sentilabs.email.Emailmsg;
import com.sentilabs.mailer.aws.AwsSesMailer;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.MessagingException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class RabbitmqToAwsSesRunnable implements Runnable {

    // variable whether connections to AWS SES and RabbitMQ have been successfully created
    private boolean inited = false;

    // instance of AwsSesMailer which holds connection to AWS SES and is used to send message
    private AwsSesMailer awsSesMailer;
    private Channel rmqChannel;
    private Connection rmqConnection;
    private QueueingConsumer consumer;

    private static Logger logger = LogManager.getLogger(RabbitmqToAwsSesRunnable.class.getName());

    // print debug info to console and log file
    private static void logAndPrint(String str){
        System.out.println(str);
        logger.error(str);
    }

    // print debug info with stack trace to console and log file
    private static void logAndPrint(String str, Exception e){
        System.out.println(str);
        logger.error(e.getMessage(), e);
        logger.error(str);
    }

    /**
     * 
     * @param awsSesFileParamsPath path to file with information required to connect to AWS SES
     * @param rabbitmqFileParams path to file with information required to connect to RabbitMQ
     * @return true if all required connections have been established, false otherwise
     */
    public boolean init(String awsSesFileParamsPath, String rabbitmqFileParams) {

        if (awsSesFileParamsPath == null){
            System.out.println("File with parameters for AWS SES not specified");
            logger.error("File with parameters for AWS SES not specified");
            return false;
        }
        if (rabbitmqFileParams == null){
            System.out.println("File with parameters for rabbitMQ not specified");
            logger.error("File with parameters for rabbitMQ not specified");
            return false;
        }
        FileInputStream fisSesParams; // input streams with params for AWS SES
        FileInputStream fisRmqParams; // input streams with params for RabbitMQ
        Properties rabbitmqProperties = new Properties();

        try {
            fisSesParams = new FileInputStream(awsSesFileParamsPath);
        } catch (FileNotFoundException e) {
            logAndPrint("Unable to open file " + awsSesFileParamsPath + " for AWS SES parameters", e);
            return false;
        }

        try {
            fisRmqParams = new FileInputStream(rabbitmqFileParams);
        } catch (FileNotFoundException e) {
            logAndPrint("Unable to open file " + rabbitmqFileParams + " for AWS SES parameters", e);
            return false;
        }

        try {
            rabbitmqProperties.load(fisRmqParams);
        } catch (IOException e) {
            logAndPrint("Error while reading parameters from file " + rabbitmqFileParams + " for rabbitMQ", e);
            return false;
        }

        if (!rabbitmqProperties.containsKey("queueName")){
            logAndPrint("Queue name not specified in the supplied file");
            return false;
        }

        try {
            this.awsSesMailer = new AwsSesMailer(fisSesParams);
        } catch (IllegalArgumentException e) {
            logAndPrint("Specified file " + awsSesFileParamsPath + " does not provides information required to connect to AWS SES");
            return false;
        } catch (MessagingException e) {
            logAndPrint("Error while reading file " + awsSesFileParamsPath + " for AWS SES parameters or initiating connection to AWS SES");
            return false;
        } catch (IOException e) {
            logAndPrint("Error while reading file " + awsSesFileParamsPath + " for AWS SES parameters or initiating connection to AWS SES");
            return false;
        }

        SSLContext sslContext = null;

        if (rabbitmqProperties.getProperty("SSL", "0").equals("1") ){
            if (!rabbitmqProperties.containsKey("clientKeyCert")){
                logAndPrint("With ssl enabled you must specify property clientKeyCert" +
                        " which is the path to the client certificate in p12 format");
                return false;
            }
            if (!rabbitmqProperties.containsKey("trustKeystore")){
                logAndPrint("With ssl enabled you must specify property trustKeystore" +
                        " which is the path to the trust store");
                return false;
            }
            char[] keyPassphrase = rabbitmqProperties.getProperty("clientKeyPassphrase", "").toCharArray();
            KeyStore ks;
            try {
                ks = KeyStore.getInstance("PKCS12");
            } catch (KeyStoreException e) {
                logAndPrint("Error while trying to get instance of PKCS12 keystore", e);
                return false;
            }
            try {
                ks.load(new FileInputStream(rabbitmqProperties.getProperty("clientKeyCert")), keyPassphrase);
            } catch (IOException e) {
                logAndPrint("Error while trying to read client certificate file "
                        + rabbitmqProperties.getProperty("clientKeyCert"), e);
                return false;
            } catch (NoSuchAlgorithmException e) {
                logAndPrint("NoSuchAlgorithmException error while loading client certificate", e);
                return false;
            } catch (CertificateException e) {
                logAndPrint("CertificateException error while loading client certificate", e);
                return false;
            }

            KeyManagerFactory kmf;
            try {
                kmf = KeyManagerFactory.getInstance("SunX509");
            } catch (NoSuchAlgorithmException e) {
                logAndPrint("Error while trying to get instance of SunX509", e);
                return false;
            }
            try {
                kmf.init(ks, keyPassphrase);
            } catch (KeyStoreException e) {
                logAndPrint("Error with keystore while trying to init KeyManagerFactory", e);
                return false;
            } catch (NoSuchAlgorithmException e) {
                logAndPrint("NoSuchAlgorithmException error while trying to init KeyManagerFactory", e);
                return false;
            } catch (UnrecoverableKeyException e) {
                logAndPrint("UnrecoverableKeyException while trying to init KeyManagerFactory", e);
                return false;
            }

            char[] trustPassphrase = rabbitmqProperties.getProperty("trustKeystorePassphrase").toCharArray();
            KeyStore tks;
            try {
                tks = KeyStore.getInstance("JKS");
            } catch (KeyStoreException e) {
                logAndPrint("Error while trying to get instance of JKS keystore");
                return false;
            }
            try {
                tks.load(new FileInputStream(rabbitmqProperties.getProperty("trustKeystore")), trustPassphrase);
            } catch (IOException e) {
                logAndPrint("Error while trying to read trust store file "
                        + rabbitmqProperties.getProperty("trustRabbitmqStore"), e);
                return false;
            } catch (NoSuchAlgorithmException e) {
                logAndPrint("NoSuchAlgorithmException error while loading trust store", e);
                return false;
            } catch (CertificateException e) {
                logAndPrint("CertificateException error while loading trust store", e);
                return false;
            }

            TrustManagerFactory tmf;
            try {
                tmf = TrustManagerFactory.getInstance("SunX509");
            } catch (NoSuchAlgorithmException e) {
                logAndPrint("Error while trying to get instance of SunX509", e);
                return false;
            }
            try {
                tmf.init(tks);
            } catch (KeyStoreException e) {
                logAndPrint("Error while trying to init instance of SunX509 with trust keystore", e);
                return false;
            }

            try {
                sslContext = SSLContext.getInstance("SSLv3");
            } catch (NoSuchAlgorithmException e) {
                logAndPrint("NoSuchAlgorithmException error while trying to get instance of SSLv3", e);
                return false;
            }
            try {
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            } catch (KeyManagementException e) {
                logAndPrint("KeyManagementException error while trying to init ssl context", e);
                return false;
            }
        }

        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(rabbitmqProperties.getProperty("host", "localhost"));
        connectionFactory.setPort(Integer.valueOf(rabbitmqProperties.getProperty("port", "5672")));
        connectionFactory.setUsername(rabbitmqProperties.getProperty("username", "guest"));
        connectionFactory.setPassword(rabbitmqProperties.getProperty("password", "guest"));
        connectionFactory.setVirtualHost(rabbitmqProperties.getProperty("vhost", "/"));

        if (sslContext != null){
            connectionFactory.useSslProtocol(sslContext);
        }
        try {
            logger.debug("Connecting to " + connectionFactory.getHost() + ":" + connectionFactory.getPort() +
                    " with user=" + connectionFactory.getUsername() + " to vhost=" + connectionFactory.getVirtualHost());
            this.rmqConnection = connectionFactory.newConnection();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            logger.error("Error while getting connection");
            return false;
        }

        try {
            this.rmqChannel = this.rmqConnection.createChannel();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            logger.error("Error while creating channel");
            return false;
        }

        this.consumer = new QueueingConsumer(this.rmqChannel);
        try {
            this.rmqChannel.basicConsume(rabbitmqProperties.getProperty("queueName"), false, consumer);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            logger.error("Error while trying to init consume");
            return false;
        }
        this.inited = true;
        return this.inited;
    }

    public void close() {
        try {
            this.rmqChannel.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            logger.error("There was an error while trying to close the channel");
        }
        try {
            this.rmqConnection.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            logger.error("There was an error while trying to close connection");
        }
        try {
            this.awsSesMailer.close();
        } catch (MessagingException e) {
            logger.error(e.getMessage(), e);
            logger.error("There was an error while trying to close AWS SES connection");
        }
    }

    @Override
    public void run() {
        if (!this.inited){
            logger.error("Trying to run uninitialized");
            return;
        }
        logger.error("Entering loop");
        while (true) {
            QueueingConsumer.Delivery delivery;
            try {
                delivery = this.consumer.nextDelivery();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                logger.error("There was an error while trying to get next message");
                return;
            } catch (ShutdownSignalException e){
                continue;
            }
            byte[] bytes = delivery.getBody();
            Emailmsg.EmailMsg msg;
            try {
                msg = Emailmsg.EmailMsg.parseFrom(bytes);
            } catch (InvalidProtocolBufferException e) {
                logger.error("Malformed emailmsg");
                continue;
            }
            try {
                this.awsSesMailer.sendMail(msg.getFrom(), msg.getTo(), msg.getSubject(), msg.getText());
            }
            catch (MessagingException e){
                logger.error("Error while trying to send mail from " + msg.getFrom() + " to " + msg.getTo() + " with subject"
                        + msg.getSubject());
            }
            try {
                this.rmqChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                logger.error("There was an error while ack the message");
                return;
            }
        }
    }

    @Override
    protected void finalize(){
        try {
            super.finalize();
        } catch (Throwable throwable) {
            logger.error("Error while finalizing super", throwable);
        }
        this.close();
    }
}
