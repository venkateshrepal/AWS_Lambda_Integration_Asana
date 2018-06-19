package com.amazonaws.lambda.demo;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Flags;
import javax.mail.Flags.Flag;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimePart;
import javax.mail.search.FlagTerm;
import javax.mail.util.ByteArrayDataSource;
import javax.servlet.http.HttpSession;

import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.waiters.HttpSuccessStatusAcceptor;
import com.google.common.io.Files;
import com.sun.mail.util.BASE64DecoderStream;

public class LambdaFunctionHandler implements RequestHandler<Object, String> {
	private static String saveDirectory;

	@Override
	public String handleRequest(Object input, Context context) {
		context.getLogger().log("Input: " + input);
		main(null);
		// TODO: implement your handler
		return "please check log!";
	}

	public static JSONObject check(String host, String storeType, String user, String password) {
		JSONObject jsonOutput = new JSONObject();
		try {
			DataSource source =null;
			MimeBodyPart part = null;
			String fileName = null ;
			ObjectMetadata metadata = new ObjectMetadata();
			HttpSession sessionToSaveFiles = null;
			byte[] bytes=null;
			// create properties field
			Properties properties = new Properties();
			Session session = Session.getDefaultInstance(new Properties( ));
		    Store store = session.getStore("imaps");
		    store.connect(host, 993, user, password);
			// create the folder object and open it
			Folder emailFolder = store.getFolder("INBOX");
			emailFolder.open(Folder.READ_WRITE);
			
			Folder seenFolder = store.getFolder("SeenMails");
			seenFolder.open(Folder.READ_WRITE);
			Message[] messages = emailFolder.search(new FlagTerm(new Flags(Flag.SEEN), false));
			System.out.println("messages.length---" + messages.length);
			if (messages.length > 0) {
				emailFolder.copyMessages(messages, seenFolder);
				for (int i = 0, n = messages.length; i < n; i++) {
					javax.mail.Message message = messages[i];
					String contentType = message.getContentType();
					String messageContent = "";
					messages[i].setFlag(Flag.SEEN, true);
					// store attachment file name, separated by comma
					String attachFiles = "";
					if (contentType.contains("multipart")) {
						// content may contain attachments
						Multipart multiPart = (Multipart) message.getContent();
						int numberOfParts = multiPart.getCount();
						for (int partCount = 0; partCount < numberOfParts; partCount++) {
							 part = (MimeBodyPart) multiPart.getBodyPart(partCount);
							if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
								InputStream stream = part.getRawInputStream();
								InputStream is = part.getInputStream();
								bytes = IOUtils.toByteArray(is);
								 fileName = part.getFileName();
								attachFiles += fileName + ", ";
							} else {
								// this part may be the message content
								messageContent = part.getContent().toString();
							}
						}
						if (attachFiles.length() > 1) {
							attachFiles = attachFiles.substring(0, attachFiles.length() - 2);
						}
					} else if (contentType.contains("text/plain") || contentType.contains("text/html")) {
						Object content = message.getContent();
						if (content != null) {
							messageContent = content.toString();
						}
					}
					System.out.println("---------------------------------");
					System.out.println("Email Number " + (i + 1));
					System.out.println("Subject: " + message.getSubject());
					System.out.println("From: " + message.getFrom()[0]);
					System.out.println("Text: " + message.getContent().toString());

					if (message.getSubject().toString().toLowerCase().contains("resume")
							|| message.getSubject().toString().toLowerCase().contains("cv")
							|| message.getSubject().toString().toLowerCase().contains("biodata")) {

						try {
							String url = "https://app.asana.com/api/1.0/tasks";
							@SuppressWarnings("deprecation")
							HttpClient client = new DefaultHttpClient();
							HttpPost post = new HttpPost(url);
							post.setHeader("Authorization", "Bearer 0/94c97976672d47ec8c4198bed85722b1");
							// add required parameters to API
							JSONObject jsonInput = new JSONObject();
							JSONObject jsonDataInput = new JSONObject();
							JSONObject jsonArrayInput = new JSONObject();
							JSONArray arrayInput = new JSONArray();
							jsonInput.put("workspace", System.getenv("workspace"));
							String [] mailName = message.getFrom()[0].toString().split("<");
							jsonInput.put("name", mailName[0]);
							String notes = "Experience: "+
									"\nCTC: "+
									"\nECTC: "+
									"\nNotice Period: "+
									"\nSkype: "+
									"\nEmail: " + mailName[1].replace(">", "");
							jsonInput.put("notes", notes);
							jsonInput.put("projects", System.getenv("projects"));
							jsonInput.put("assignee", System.getenv("assignee"));
							
							jsonArrayInput.put("project", System.getenv("projects"));
							jsonArrayInput.put("section", System.getenv("section"));
							arrayInput.put(jsonArrayInput);
							jsonInput.put("memberships", arrayInput);
							
							jsonDataInput.put("data", jsonInput);
							System.out.println(jsonDataInput);
							
							post.setEntity(new StringEntity(jsonDataInput.toJSONString()));
							HttpResponse responsePost = client.execute(post);
							System.out.println("\nSending 'POST' request to URL : " + url);
							System.out.println("Post parameters : " + responsePost.getEntity());
							System.out.println("Response Code : " + responsePost.getStatusLine().getStatusCode());
							BufferedReader rdPost = new BufferedReader(new InputStreamReader(responsePost.getEntity().getContent()));
							StringBuffer resultPost = new StringBuffer();
							String linePost = "";
							while ((linePost = rdPost.readLine()) != null) {
								resultPost.append(linePost);
							}
							System.out.println(resultPost.toString());
							JSONParser parserPost = new JSONParser();
							jsonOutput = (JSONObject) parserPost.parse(resultPost.toString());
							
							//------------------------------------------------------------------------------
							System.out.println(jsonOutput.get("data"));
							
							JSONObject outputData = (JSONObject) jsonOutput.get("data");
							Long taskId = (Long) outputData.get("id");
							String numberAsStringTaskId = Long.toString(taskId);
							System.out.println("This is the task id we need to send::::" +numberAsStringTaskId);
							//--------------------------------------------------------------------------------------------------------
							// to create an attachment to a task
							String urltaskAttachment = "https://app.asana.com/api/1.0/tasks/"+numberAsStringTaskId+"/attachments";
							HttpClient clientTaskAttachment = new DefaultHttpClient();
							HttpPost postTaskAttachment = new HttpPost(urltaskAttachment);
							postTaskAttachment.setHeader("Authorization", System.getenv("authorization"));
									//"Bearer 0/94c97976672d47ec8c4198bed85722b1"
							MultipartEntityBuilder builder = MultipartEntityBuilder.create();
							builder.addTextBody("task", numberAsStringTaskId);
							ByteArrayOutputStream out = new ByteArrayOutputStream();
						    ObjectOutputStream os = new ObjectOutputStream(out);
						    os.writeObject(metadata);
						    out.toByteArray();
						    builder.addBinaryBody("file", bytes, ContentType.DEFAULT_BINARY, part.getFileName());
							HttpEntity multipart = builder.build();
							postTaskAttachment.setEntity(multipart);
							HttpResponse response = clientTaskAttachment.execute(postTaskAttachment);
							HttpEntity responseEntity = response.getEntity();
							System.out.println("\nSending 'POST' request to URL : " + urltaskAttachment);
							System.out.println("Post parameters : " + post.getEntity());
							System.out.println("Response Code : " + response.getStatusLine().getStatusCode());
							BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
							StringBuffer result = new StringBuffer();
							String line = "";
							while ((line = rd.readLine()) != null) {
								result.append(line);
							}
							System.out.println(result.toString());
							JSONParser parser = new JSONParser();
							jsonOutput = (JSONObject) parser.parse(result.toString());
						} catch (Exception e) {
							e.printStackTrace();
							String errorMessageAndClassWithMethod = getErrorContainingClassAndMethod();
							throw new Exception(errorMessageAndClassWithMethod + e.toString());
						}
						
					} else {
						jsonOutput = (JSONObject) jsonOutput.put("result", "no emails to work on");
					}
					messages[i].setFlag(Flag.DELETED, true);
					// expunge to delete the mails which are flagged as DELETED
					emailFolder.expunge();
					emailFolder.close(false);
					/*boolean expunge = true;
					emailFolder.close(expunge);*/
					store.close();
				}return jsonOutput;

			}
			// close the store and folder objects
		} catch (MessagingException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return jsonOutput;
	}

	public static void main(String[] args) {
		String host = "imap.secureserver.net";// change accordingly
		String mailStoreType = "imaps";
		String username = System.getenv("mailId");
		String password = System.getenv("password");
		String saveDirectory = "D:/Attachment";
		LambdaFunctionHandler receiver = new LambdaFunctionHandler();
		receiver.setSaveDirectory(saveDirectory);
		check(host, mailStoreType, username, password);
	}

	public void setSaveDirectory(String dir) {
		this.saveDirectory = dir;
	}

	public static String getErrorContainingClassAndMethod() {
		final StackTraceElement e = Thread.currentThread().getStackTrace()[2];
		final String s = e.getClassName();
		String errorInMethod = s.substring(s.lastIndexOf('.') + 1, s.length()) + "." + e.getMethodName();
		return "Error in " + errorInMethod + " : ";
	}

	/**
	 * @param url
	 * @param client
	 * @param post
	 * @param urlParameters
	 * @return
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 * @throws ClientProtocolException
	 * @throws UnsupportedOperationException
	 * @throws ParseException
	 */
	private static JSONObject getPostDataOutput(String url, HttpClient client, HttpPost post,
			List<NameValuePair> urlParameters) throws UnsupportedEncodingException, IOException,
			ClientProtocolException, UnsupportedOperationException, ParseException {
		JSONObject jsonOutput;
		post.setEntity(new UrlEncodedFormEntity(urlParameters));
		HttpResponse response = client.execute(post);
		System.out.println("\nSending 'POST' request to URL : " + url);
		System.out.println("Post parameters : " + post.getEntity());
		System.out.println("Response Code : " + response.getStatusLine().getStatusCode());
		BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
		StringBuffer result = new StringBuffer();
		String line = "";
		while ((line = rd.readLine()) != null) {
			result.append(line);
		}
		System.out.println(result.toString());
		JSONParser parser = new JSONParser();
		jsonOutput = (JSONObject) parser.parse(result.toString());
		return jsonOutput;
	}
}
