package com.easydb.client.gui;

import com.easydb.client.sdk.DatabaseClient;
import com.easydb.common.constants.Constants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuiClient extends JFrame {

    private DatabaseClient client;
    private boolean connected = false;
    
    private JTextField hostField;
    private JTextField portField;
    private JButton connectBtn;
    private JButton disconnectBtn;
    private JLabel statusLabel;
    private JTextArea resultArea;
    
    private JTextField keyInput;
    private JTextField valueInput;
    private JTextField ttlInput;
    
    private JTextField commandInput;
    private JButton sendCommandBtn;

    public GuiClient() {
        super("Easy-DB Client (客户端)");
        initUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(700, 500);
        setLocationRelativeTo(null);
    }

    private void initUI() {
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        hostField = new JTextField(Constants.DEFAULT_HOST, 15);
        portField = new JTextField(String.valueOf(Constants.DEFAULT_SOCKET_PORT), 5);
        connectBtn = new JButton("Connect (连接)");
        disconnectBtn = new JButton("Disconnect (断开)");
        disconnectBtn.setEnabled(false);
        
        statusLabel = new JLabel("Status (状态): Disconnected (未连接)");
        statusLabel.setForeground(Color.RED);
        
        topPanel.add(new JLabel("Host (主机):"));
        topPanel.add(hostField);
        topPanel.add(new JLabel("Port (端口):"));
        topPanel.add(portField);
        topPanel.add(connectBtn);
        topPanel.add(disconnectBtn);
        topPanel.add(statusLabel);
        
        add(topPanel, BorderLayout.NORTH);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Quick Operations (快捷操作)", createQuickOpsPanel());
        tabbedPane.addTab("Command Mode (命令模式)", createCommandPanel());
        add(tabbedPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        resultArea = new JTextArea(10, 50);
        resultArea.setEditable(false);
        resultArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        bottomPanel.add(new JScrollPane(resultArea), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
    }

    private JPanel createQuickOpsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Key (键名):"), gbc);
        
        gbc.gridx = 1; gbc.gridwidth = 2;
        keyInput = new JTextField(20);
        panel.add(keyInput, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
        panel.add(new JLabel("Value (值):"), gbc);
        
        gbc.gridx = 1; gbc.gridwidth = 2;
        valueInput = new JTextField(20);
        panel.add(valueInput, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        panel.add(new JLabel("TTL (过期时间):"), gbc);
        
        gbc.gridx = 1; gbc.gridwidth = 2;
        ttlInput = new JTextField("0", 20);
        panel.add(ttlInput, gbc);

        JPanel btnPanel = new JPanel(new FlowLayout());
        JButton setBtn = new JButton("SET (设置)");
        JButton getBtn = new JButton("GET (获取)");
        JButton delBtn = new JButton("DEL (删除)");
        JButton existsBtn = new JButton("EXISTS (检查)");
        JButton keysBtn = new JButton("KEYS (列表)");
        JButton msetBtn = new JButton("MSET (批量设置)");
        JButton mgetBtn = new JButton("MGET (批量获取)");
        JButton mdelBtn = new JButton("MDEL (批量删除)");
        
        setBtn.addActionListener(e -> executeSet());
        getBtn.addActionListener(e -> executeGet());
        delBtn.addActionListener(e -> executeDel());
        existsBtn.addActionListener(e -> executeExists());
        keysBtn.addActionListener(e -> executeKeys());
        msetBtn.addActionListener(e -> executeMSet());
        mgetBtn.addActionListener(e -> executeMGet());
        mdelBtn.addActionListener(e -> executeMDel());
        
        btnPanel.add(setBtn);
        btnPanel.add(getBtn);
        btnPanel.add(delBtn);
        btnPanel.add(existsBtn);
        btnPanel.add(keysBtn);
        btnPanel.add(msetBtn);
        btnPanel.add(mgetBtn);
        btnPanel.add(mdelBtn);
        
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3;
        panel.add(btnPanel, gbc);

        return panel;
    }

    private JPanel createCommandPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        commandInput = new JTextField(40);
        sendCommandBtn = new JButton("Send (发送)");
        
        inputPanel.add(new JLabel("Command (命令):"));
        inputPanel.add(commandInput);
        inputPanel.add(sendCommandBtn);
        
        panel.add(inputPanel, BorderLayout.NORTH);
        
        sendCommandBtn.addActionListener(e -> executeCommand());
        commandInput.addActionListener(e -> executeCommand());
        
        return panel;
    }

    private void connect() {
        try {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());
            
            client = new DatabaseClient(host, port);
            client.connect();
            
            connected = true;
            connectBtn.setEnabled(false);
            disconnectBtn.setEnabled(true);
            statusLabel.setText("Status (状态): Connected (已连接)");
            statusLabel.setForeground(Color.GREEN);
            resultArea.append("Connected (已连接) to " + host + ":" + port + "\n");
        } catch (Exception e) {
            resultArea.append("Connection failed (连接失败): " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, "Connection failed (连接失败): " + e.getMessage(), "Error (错误)", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disconnect() {
        if (client != null) {
            client.close();
        }
        connected = false;
        connectBtn.setEnabled(true);
        disconnectBtn.setEnabled(false);
        statusLabel.setText("Status (状态): Disconnected (未连接)");
        statusLabel.setForeground(Color.RED);
        resultArea.append("Disconnected (已断开)\n");
    }

    private void executeSet() {
        if (!connected) {
            showNotConnected();
            return;
        }
        try {
            String key = keyInput.getText().trim();
            String value = valueInput.getText().trim();
            if (key.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Key cannot be empty (键名不能为空)", "Error (错误)", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String result = client.set(key, value);
            resultArea.append("> SET (设置) " + key + " " + value + "\n");
            resultArea.append("< " + result + "\n");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeGet() {
        if (!connected) {
            showNotConnected();
            return;
        }
        try {
            String key = keyInput.getText().trim();
            if (key.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Key cannot be empty (键名不能为空)", "Error (错误)", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String value = client.get(key);
            resultArea.append("> GET (获取) " + key + "\n");
            resultArea.append("< " + (value != null ? "\"" + value + "\"" : "(nil 空)") + "\n");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeDel() {
        if (!connected) {
            showNotConnected();
            return;
        }
        try {
            String key = keyInput.getText().trim();
            if (key.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Key cannot be empty (键名不能为空)", "Error (错误)", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            String result = client.del(key);
            resultArea.append("> DEL (删除) " + key + "\n");
            resultArea.append("< " + result + "\n");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeExists() {
        if (!connected) {
            showNotConnected();
            return;
        }
        try {
            String key = keyInput.getText().trim();
            if (key.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Key cannot be empty (键名不能为空)", "Error (错误)", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            boolean exists = client.exists(key);
            resultArea.append("> EXISTS (检查) " + key + "\n");
            resultArea.append("< " + (exists ? "1 (存在)" : "0 (不存在)") + "\n");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeKeys() {
        if (!connected) {
            showNotConnected();
            return;
        }
        try {
            List<String> keys = client.keys("*");
            resultArea.append("> KEYS (列表) *\n");
            if (keys.isEmpty()) {
                resultArea.append("< (empty list or set 空列表)\n");
            } else {
                int idx = 1;
                for (String key : keys) {
                    resultArea.append("< " + idx + ") \"" + key + "\"\n");
                    idx++;
                }
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeMSet() {
        if (!connected) {
            showNotConnected();
            return;
        }
        String input = JOptionPane.showInputDialog(this, "Enter key-value pairs (format: key1 value1 key2 value2 ...)", "MSET (批量设置)", JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        try {
            String[] parts = input.trim().split("\\s+");
            if (parts.length < 4 || parts.length % 2 != 0) {
                JOptionPane.showMessageDialog(this, "Invalid format. Need key1 value1 key2 value2 ...", "Error (错误)", JOptionPane.ERROR_MESSAGE);
                return;
            }
            Map<String, String> entries = new HashMap<>();
            for (int i = 0; i < parts.length; i += 2) {
                entries.put(parts[i], parts[i + 1]);
            }
            client.mset(entries);
            resultArea.append("> MSET (批量设置) " + input + "\n");
            resultArea.append("< OK\n");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeMGet() {
        if (!connected) {
            showNotConnected();
            return;
        }
        String input = JOptionPane.showInputDialog(this, "Enter keys separated by spaces:", "MGET (批量获取)", JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        try {
            String[] parts = input.trim().split("\\s+");
            List<String> keys = new ArrayList<>();
            for (String part : parts) {
                keys.add(part);
            }
            List<String> values = client.mget(keys);
            resultArea.append("> MGET (批量获取) " + input + "\n");
            int idx = 1;
            for (String value : values) {
                resultArea.append("< " + idx + ") " + (value != null ? "\"" + value + "\"" : "(nil)") + "\n");
                idx++;
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeMDel() {
        if (!connected) {
            showNotConnected();
            return;
        }
        String input = JOptionPane.showInputDialog(this, "Enter keys separated by spaces:", "MDEL (批量删除)", JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        try {
            String[] parts = input.trim().split("\\s+");
            List<String> keys = new ArrayList<>();
            for (String part : parts) {
                keys.add(part);
            }
            client.mdel(keys);
            resultArea.append("> MDEL (批量删除) " + input + "\n");
            resultArea.append("< OK\n");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void executeCommand() {
        if (!connected) {
            showNotConnected();
            return;
        }
        try {
            String cmd = commandInput.getText().trim();
            if (cmd.isEmpty()) {
                return;
            }
            
            resultArea.append("> " + cmd + "\n");
            
            String[] parts = cmd.split("\\s+");
            String command = parts[0].toUpperCase();
            
            switch (command) {
                case "SET":
                    if (parts.length < 3) {
                        resultArea.append("< (error) ERR Usage: SET key value\n");
                        return;
                    }
                    String key = parts[1];
                    StringBuilder value = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        if (i > 2) value.append(" ");
                        value.append(parts[i]);
                    }
                    resultArea.append("< " + client.set(key, value.toString()) + "\n");
                    break;
                case "GET":
                    if (parts.length < 2) {
                        resultArea.append("< (error) ERR Usage: GET key\n");
                        return;
                    }
                    String getValue = client.get(parts[1]);
                    resultArea.append("< " + (getValue != null ? "\"" + getValue + "\"" : "(nil)") + "\n");
                    break;
                case "DEL":
                    if (parts.length < 2) {
                        resultArea.append("< (error) ERR Usage: DEL key\n");
                        return;
                    }
                    resultArea.append("< " + client.del(parts[1]) + "\n");
                    break;
                case "KEYS":
                    String pattern = parts.length >= 2 ? parts[1] : "*";
                    List<String> keys = client.keys(pattern);
                    if (keys.isEmpty()) {
                        resultArea.append("< (empty list or set)\n");
                    } else {
                        int idx = 1;
                        for (String k : keys) {
                            resultArea.append("< " + idx + ") \"" + k + "\"\n");
                            idx++;
                        }
                    }
                    break;
                case "EXISTS":
                    if (parts.length < 2) {
                        resultArea.append("< (error) ERR Usage: EXISTS key\n");
                        return;
                    }
                    resultArea.append("< " + (client.exists(parts[1]) ? "1" : "0") + "\n");
                    break;
                case "MSET":
                    if (parts.length < 4 || (parts.length - 1) % 2 != 0) {
                        resultArea.append("< (error) ERR Usage: MSET key1 value1 key2 value2 ...\n");
                        return;
                    }
                    Map<String, String> entries = new HashMap<>();
                    for (int i = 1; i < parts.length; i += 2) {
                        entries.put(parts[i], parts[i + 1]);
                    }
                    client.mset(entries);
                    resultArea.append("< OK\n");
                    break;
                case "MGET":
                    if (parts.length < 2) {
                        resultArea.append("< (error) ERR Usage: MGET key1 key2 ...\n");
                        return;
                    }
                    List<String> mgetKeys = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        mgetKeys.add(parts[i]);
                    }
                    List<String> mgetValues = client.mget(mgetKeys);
                    int idx = 1;
                    for (String value : mgetValues) {
                        resultArea.append("< " + idx + ") " + (value != null ? "\"" + value + "\"" : "(nil)") + "\n");
                        idx++;
                    }
                    break;
                case "MDEL":
                    if (parts.length < 2) {
                        resultArea.append("< (error) ERR Usage: MDEL key1 key2 ...\n");
                        return;
                    }
                    List<String> mdelKeys = new ArrayList<>();
                    for (int i = 1; i < parts.length; i++) {
                        mdelKeys.add(parts[i]);
                    }
                    client.mdel(mdelKeys);
                    resultArea.append("< OK\n");
                    break;
                default:
                    resultArea.append("< (error) ERR Unknown command: " + command + "\n");
            }
            
            commandInput.setText("");
        } catch (Exception e) {
            handleError(e);
        }
    }

    private void showNotConnected() {
        JOptionPane.showMessageDialog(this, "Please connect to server first (请先连接服务器)", "Error (错误)", JOptionPane.WARNING_MESSAGE);
    }

    private void handleError(Exception e) {
        resultArea.append("< (error 错误) ERR " + e.getMessage() + "\n");
        JOptionPane.showMessageDialog(this, "Error (错误): " + e.getMessage(), "Error (错误)", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new GuiClient().setVisible(true);
        });
    }
}
