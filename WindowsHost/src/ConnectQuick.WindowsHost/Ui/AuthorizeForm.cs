using ConnectQuick.WindowsHost.Pairing;

namespace ConnectQuick.WindowsHost.Ui;

/// <summary>The "¿Autorizar?" dialog (Protocol §7 step 5) — shows the requesting device's name and the SAS code for out-of-band comparison against what the Controller shows mid-scan.</summary>
public sealed class AuthorizeForm : Form
{
    public bool Approved { get; private set; }

    public AuthorizeForm(IncomingPairingRequest request)
    {
        Text = "ConnectQuick — Solicitud de vinculación";
        Width = 380;
        Height = 240;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true;

        var message = new Label
        {
            Text = $"\"{request.DeviceName}\" quiere vincularse con este PC.\n\n¿Coincide este código con el que muestra tu teléfono?",
            AutoSize = false,
            Dock = DockStyle.Top,
            Height = 90,
            TextAlign = System.Drawing.ContentAlignment.MiddleCenter,
            Padding = new Padding(10),
        };

        var sasLabel = new Label
        {
            Text = FormatSas(request.SasCode),
            AutoSize = false,
            Dock = DockStyle.Top,
            Height = 50,
            TextAlign = System.Drawing.ContentAlignment.MiddleCenter,
            Font = new System.Drawing.Font(Font.FontFamily, 20, System.Drawing.FontStyle.Bold),
        };

        var buttonPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            Height = 50,
            FlowDirection = FlowDirection.RightToLeft,
            Padding = new Padding(10),
        };

        var yesButton = new Button { Text = "Sí, autorizar", DialogResult = DialogResult.Yes, AutoSize = true };
        var noButton = new Button { Text = "No", DialogResult = DialogResult.No, AutoSize = true };
        yesButton.Click += (_, _) => { Approved = true; Close(); };
        noButton.Click += (_, _) => { Approved = false; Close(); };
        buttonPanel.Controls.Add(yesButton);
        buttonPanel.Controls.Add(noButton);

        AcceptButton = yesButton;
        CancelButton = noButton;

        Controls.Add(sasLabel);
        Controls.Add(message);
        Controls.Add(buttonPanel);
    }

    private static string FormatSas(string sas) => sas.Length == 6 ? $"{sas[..3]} {sas[3..]}" : sas;
}
