// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/** A small window for programs that were started by a double click and have no console. */
public final class SwingProgress implements Progress {
    private JFrame frame;
    private JLabel label;
    private JProgressBar bar;

    public SwingProgress(final String title) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame = new JFrame(title);
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                label = new JLabel("Preparing the first start...");
                bar = new JProgressBar(0, 1000);
                bar.setIndeterminate(true);
                JPanel p = new JPanel(new BorderLayout(0, 8));
                p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
                p.add(label, BorderLayout.NORTH);
                p.add(bar, BorderLayout.CENTER);
                p.setPreferredSize(new Dimension(420, 90));
                frame.setContentPane(p);
                WindowIcon.apply(frame);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            }
        });
    }

    public void status(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                label.setText(text);
                bar.setIndeterminate(true);
            }
        });
    }

    public void progress(final long done, final long total) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (total > 0) {
                    bar.setIndeterminate(false);
                    bar.setValue((int) (done * 1000L / total));
                } else {
                    bar.setIndeterminate(true);
                }
            }
        });
    }

    public void done() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                frame.dispose();
            }
        });
    }
}
