package cc4p1.clientchat;

/**
 *
 * @author Albert
 */
public class ClientChatMain {
        public static void main(String args[]) {
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new ClientChatGUI().setVisible(true);
            }
        });
    }
}
