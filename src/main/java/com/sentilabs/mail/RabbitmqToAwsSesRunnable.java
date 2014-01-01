package com.sentilabs.mail;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sentilabs.email.Emailmsg;
import com.sentilabs.helpers.rabbitmq.RabbitMQConnection;
import com.sentilabs.helpers.rabbitmq.RabbitMQMessageData;
import com.sentilabs.mailer.aws.AwsSesMailer;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.mail.MessagingException;

public class RabbitmqToAwsSesRunnable implements Runnable {

    // variable whether connections to AWS SES and RabbitMQ have been successfully created
    private boolean inited = false;

    // instance of AwsSesMailer which holds connection to AWS SES and is used to send message
    private AwsSesMailer awsSesMailer;
    private RabbitMQConnection rabbitMQConnection;

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

        try {
            this.rabbitMQConnection = new RabbitMQConnection(rabbitmqFileParams);
        } catch (Exception e) {
            logAndPrint("Error while initializing connection to rabbitMQ", e);
            return false;
        }

        FileInputStream fisSesParams; // input streams with params for AWS SES

        try {
            fisSesParams = new FileInputStream(awsSesFileParamsPath);
        } catch (FileNotFoundException e) {
            logAndPrint("Unable to open file " + awsSesFileParamsPath + " for AWS SES parameters", e);
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

        try {
            logger.debug("Connecting to " + this.rabbitMQConnection.getHost() + ":" + this.rabbitMQConnection.getPort() +
                    " with user=" + this.rabbitMQConnection.getUsername() + " to vhost=" + this.rabbitMQConnection.getVirtualHost());
            this.rabbitMQConnection.connect(false);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            logger.error("Error while getting connection");
            return false;
        }

        this.inited = true;
        return true;
    }

    public void close() {
        try {
            this.rabbitMQConnection.close();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            logger.error("There was an error while trying to close the channel");
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
            RabbitMQMessageData data = new RabbitMQMessageData();
            try {
                this.rabbitMQConnection.nextData(data);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                logger.error("There was an error while trying to get next message");
                return;
            } catch (Exception e){
                logger.error(e.getMessage(), e);
                logger.error("Got shutdown from RabbitMQ");
            }
            Emailmsg.EmailMsg msg;
            try {
                msg = Emailmsg.EmailMsg.parseFrom(data.getBytes());
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
                this.rabbitMQConnection.basicAck(data);
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
