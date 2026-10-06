import tkinter as tk
from tkinter import ttk
import psutil
import subprocess
import threading
import time
import platform
import socket
import webbrowser
import os
import sys
from datetime import datetime


# ============================================================
# WINDOWS APPLICATION IDENTITY
# ============================================================

if sys.platform == "win32":
    try:
        import ctypes

        # Give SysPulse its own Windows application identity
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            "Athuldas.SysPulse"
        )

    except Exception:
        pass

# ============================================================
# COLORS
# ============================================================

BG = "#101114"
CARD = "#181A20"
CARD_BORDER = "#292C34"
TEXT = "#F2F2F2"
SUBTEXT = "#8F939D"

GREEN = "#45D483"
ORANGE = "#FFB454"
RED = "#FF5C5C"
BLUE = "#6C63FF"


# ============================================================
# SYSTEM INFORMATION
# ============================================================

boot_time = psutil.boot_time()

hostname = socket.gethostname()

system_name = platform.system()
system_version = platform.release()

physical_cpus = psutil.cpu_count(logical=False) or 0
logical_cpus = psutil.cpu_count(logical=True) or 0

processor_name = platform.processor()

try:
    cpu_frequency = psutil.cpu_freq()
except Exception:
    cpu_frequency = None


# ============================================================
# NETWORK
# ============================================================

previous_net = psutil.net_io_counters()
previous_time = time.time()


# ============================================================
# SHARED DATA
# ============================================================

gpu_data = {
    "available": False,
    "name": "NVIDIA GPU",
    "usage": None,
    "temperature": None,
    "memory_used": None,
    "memory_total": None
}

gpu_lock = threading.Lock()

process_data = []

process_lock = threading.Lock()

system_data = {
    "cpu": 0,
    "ram": 0,
    "ram_used": 0,
    "ram_total": 0,
    "ram_available": 0,
    "disk": 0,
    "disk_used": 0,
    "disk_total": 0,
    "disk_free": 0,
    "gpu": 0,
    "gpu_temp": None,
    "battery": None,
    "battery_charging": False,
    "download": 0,
    "upload": 0
}

data_lock = threading.Lock()


# ============================================================
# HELPERS
# ============================================================

def usage_color(percent):

    if percent >= 90:
        return RED

    if percent >= 70:
        return ORANGE

    return GREEN


def format_bytes(value):

    if value is None:
        return "N/A"

    value = float(value)

    units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB"
    ]

    for unit in units:

        if value < 1024:
            return f"{value:.1f} {unit}"

        value /= 1024

    return f"{value:.1f} PB"


def format_uptime(seconds):

    seconds = int(seconds)

    days = seconds // 86400

    seconds %= 86400

    hours = seconds // 3600

    seconds %= 3600

    minutes = seconds // 60

    return f"{days}d {hours}h {minutes}m"


def html_escape(text):

    text = str(text)

    return (
        text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
    )


# ============================================================
# WINDOW
# ============================================================

root = tk.Tk()

# ============================================================
# SYS PULSE ICON
# ============================================================

if getattr(sys, "frozen", False):
    icon_path = os.path.join(
        sys._MEIPASS,
        "icon.ico"
    )
else:
    icon_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "icon.ico"
    )

try:
    # Set the icon used by the window
    root.iconbitmap(default=icon_path)

    # Explicitly set the window icon as well
    root.wm_iconbitmap(icon_path)

except Exception as e:
    print("Icon error:", e)

root.title("SysPulse")

root.geometry("1050x850")

root.minsize(950, 750)

root.configure(bg=BG)


# ============================================================
# HEADER
# ============================================================

header = tk.Frame(
    root,
    bg=BG
)

header.pack(
    fill="x",
    padx=30,
    pady=(22, 5)
)


title = tk.Label(
    header,
    text="SysPulse",
    font=("Segoe UI", 23, "bold"),
    bg=BG,
    fg=TEXT
)

title.pack(side="left")


status_label = tk.Label(
    header,
    text="● Monitoring",
    font=("Segoe UI", 10),
    bg=BG,
    fg=GREEN
)

status_label.pack(
    side="right",
    pady=8
)


computer_label = tk.Label(
    root,
    text=f"{hostname}  •  {system_name} {system_version}",
    font=("Segoe UI", 10),
    bg=BG,
    fg=SUBTEXT
)

computer_label.pack(
    anchor="w",
    padx=32
)


# ============================================================
# HEALTH HEADER
# ============================================================

health_frame = tk.Frame(
    root,
    bg=CARD,
    highlightbackground=CARD_BORDER,
    highlightthickness=1
)

health_frame.pack(
    fill="x",
    padx=29,
    pady=(15, 10)
)


health_left = tk.Frame(
    health_frame,
    bg=CARD
)

health_left.pack(
    side="left",
    padx=20,
    pady=14
)


health_title = tk.Label(
    health_left,
    text="SYSTEM HEALTH",
    font=("Segoe UI", 9, "bold"),
    bg=CARD,
    fg=SUBTEXT
)

health_title.pack(
    anchor="w"
)


health_score = tk.Label(
    health_left,
    text="--",
    font=("Segoe UI", 30, "bold"),
    bg=CARD,
    fg=GREEN
)

health_score.pack(
    anchor="w"
)


health_status = tk.Label(
    health_frame,
    text="Analyzing system...",
    font=("Segoe UI", 12, "bold"),
    bg=CARD,
    fg=TEXT
)

health_status.pack(
    side="left",
    padx=20
)


# ============================================================
# REPORT BUTTON
# ============================================================

report_button = tk.Button(
    health_frame,
    text="🩺  Full SysPulse Health Report",
    font=("Segoe UI", 10, "bold"),
    bg=BLUE,
    fg="white",
    activebackground="#574FE0",
    activeforeground="white",
    relief="flat",
    cursor="hand2",
    padx=15,
    pady=9,
    command=lambda: generate_report()
)

report_button.pack(
    side="right",
    padx=18
)


# ============================================================
# MAIN CARDS
# ============================================================

main = tk.Frame(
    root,
    bg=BG
)

main.pack(
    fill="x",
    padx=22,
    pady=8
)


def create_card(parent, title_text, row, column):

    card = tk.Frame(
        parent,
        bg=CARD,
        highlightbackground=CARD_BORDER,
        highlightthickness=1
    )

    card.grid(
        row=row,
        column=column,
        padx=7,
        pady=7,
        sticky="nsew"
    )

    title = tk.Label(
        card,
        text=title_text,
        font=("Segoe UI", 10),
        bg=CARD,
        fg=SUBTEXT
    )

    title.pack(
        anchor="w",
        padx=17,
        pady=(12, 2)
    )

    value = tk.Label(
        card,
        text="--",
        font=("Segoe UI", 21, "bold"),
        bg=CARD,
        fg=TEXT
    )

    value.pack(
        anchor="w",
        padx=17
    )

    detail = tk.Label(
        card,
        text="",
        font=("Segoe UI", 9),
        bg=CARD,
        fg=SUBTEXT
    )

    detail.pack(
        anchor="w",
        padx=17,
        pady=(2, 12)
    )

    return value, detail


for column in range(2):

    main.grid_columnconfigure(
        column,
        weight=1
    )


cpu_value, cpu_detail = create_card(
    main,
    "CPU",
    0,
    0
)

ram_value, ram_detail = create_card(
    main,
    "Memory",
    0,
    1
)

gpu_value, gpu_detail = create_card(
    main,
    "NVIDIA GPU",
    1,
    0
)

temperature_value, temperature_detail = create_card(
    main,
    "GPU Temperature",
    1,
    1
)

disk_value, disk_detail = create_card(
    main,
    "Disk",
    2,
    0
)

network_value, network_detail = create_card(
    main,
    "Network",
    2,
    1
)

battery_value, battery_detail = create_card(
    main,
    "Battery",
    3,
    0
)

uptime_value, uptime_detail = create_card(
    main,
    "System Uptime",
    3,
    1
)


# ============================================================
# ALERT PANEL
# ============================================================

alert_frame = tk.Frame(
    root,
    bg=CARD,
    highlightbackground=CARD_BORDER,
    highlightthickness=1
)

alert_frame.pack(
    fill="x",
    padx=29,
    pady=(3, 10)
)


alert_header = tk.Frame(
    alert_frame,
    bg=CARD
)

alert_header.pack(
    fill="x"
)


alert_title = tk.Label(
    alert_header,
    text="System Alerts",
    font=("Segoe UI", 11, "bold"),
    bg=CARD,
    fg=TEXT
)

alert_title.pack(
    side="left",
    padx=17,
    pady=10
)


alert_count = tk.Label(
    alert_header,
    text="0",
    font=("Segoe UI", 9, "bold"),
    bg=CARD,
    fg=GREEN
)

alert_count.pack(
    side="right",
    padx=17
)


alert_box = tk.Label(
    alert_frame,
    text="✓ No problems detected",
    font=("Segoe UI", 10),
    bg=CARD,
    fg=GREEN,
    justify="left",
    anchor="w"
)

alert_box.pack(
    fill="x",
    padx=17,
    pady=(0, 12)
)


# ============================================================
# PROCESS PANEL
# ============================================================

process_frame = tk.Frame(
    root,
    bg=CARD,
    highlightbackground=CARD_BORDER,
    highlightthickness=1
)

process_frame.pack(
    fill="both",
    expand=True,
    padx=29,
    pady=(0, 22)
)


process_title = tk.Label(
    process_frame,
    text="Top Processes",
    font=("Segoe UI", 11, "bold"),
    bg=CARD,
    fg=TEXT
)

process_title.pack(
    anchor="w",
    padx=17,
    pady=(10, 5)
)


process_table = ttk.Treeview(
    process_frame,
    columns=(
        "process",
        "cpu",
        "memory"
    ),
    show="headings",
    height=4
)


process_table.heading(
    "process",
    text="Process"
)

process_table.heading(
    "cpu",
    text="CPU"
)

process_table.heading(
    "memory",
    text="Memory"
)


process_table.column(
    "process",
    width=450
)

process_table.column(
    "cpu",
    width=120,
    anchor="center"
)

process_table.column(
    "memory",
    width=150,
    anchor="center"
)


style = ttk.Style()

try:
    style.theme_use("clam")
except Exception:
    pass


style.configure(
    "Treeview",
    background=CARD,
    foreground=TEXT,
    fieldbackground=CARD,
    borderwidth=0,
    rowheight=27,
    font=("Segoe UI", 9)
)


style.configure(
    "Treeview.Heading",
    background="#202229",
    foreground=SUBTEXT,
    borderwidth=0,
    font=("Segoe UI", 9, "bold")
)


style.map(
    "Treeview",
    background=[
        ("selected", "#2A2D38")
    ],
    foreground=[
        ("selected", TEXT)
    ]
)


process_table.pack(
    fill="x",
    padx=12,
    pady=(0, 10)
)


# ============================================================
# GPU WORKER
# ============================================================

def gpu_worker():

    while True:

        try:

            result = subprocess.run(
                [
                    "nvidia-smi",
                    "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total",
                    "--format=csv,noheader,nounits"
                ],
                capture_output=True,
                text=True,
                timeout=3,
                creationflags=subprocess.CREATE_NO_WINDOW
            )

            if result.returncode == 0:

                lines = result.stdout.strip().splitlines()

                if lines:

                    parts = [
                        part.strip()
                        for part in lines[0].split(",")
                    ]

                    if len(parts) >= 5:

                        with gpu_lock:

                            gpu_data["available"] = True

                            gpu_data["name"] = parts[0]

                            gpu_data["usage"] = float(
                                parts[1]
                            )

                            gpu_data["temperature"] = float(
                                parts[2]
                            )

                            gpu_data["memory_used"] = float(
                                parts[3]
                            )

                            gpu_data["memory_total"] = float(
                                parts[4]
                            )

            else:

                with gpu_lock:
                    gpu_data["available"] = False

        except Exception:

            with gpu_lock:
                gpu_data["available"] = False

        time.sleep(5)


# ============================================================
# PROCESS WORKER
# ============================================================

def process_worker():

    global process_data

    while True:

        new_processes = []

        try:

            for process in psutil.process_iter(
                [
                    "name",
                    "memory_percent"
                ]
            ):

                try:

                    cpu = process.cpu_percent(
                        interval=None
                    )

                    memory = process.info[
                        "memory_percent"
                    ]

                    name = process.info[
                        "name"
                    ]

                    if name:

                        new_processes.append(
                            (
                                name,
                                cpu,
                                memory
                            )
                        )

                except (
                    psutil.NoSuchProcess,
                    psutil.AccessDenied
                ):

                    continue


            new_processes.sort(
                key=lambda item: item[1],
                reverse=True
            )


            with process_lock:

                process_data = new_processes[:5]

        except Exception:
            pass

        time.sleep(3)


# ============================================================
# START WORKERS
# ============================================================

threading.Thread(
    target=gpu_worker,
    daemon=True
).start()


threading.Thread(
    target=process_worker,
    daemon=True
).start()


# ============================================================
# HEALTH ANALYSIS
# ============================================================

def calculate_health():

    with data_lock:

        cpu = system_data["cpu"]

        ram = system_data["ram"]

        disk = system_data["disk"]

        gpu = system_data["gpu"]

        gpu_temp = system_data["gpu_temp"]

        battery = system_data["battery"]

        charging = system_data["battery_charging"]


    score = 100

    alerts = []


    if cpu >= 95:

        score -= 20

        alerts.append(
            "🔴 CPU usage is critically high."
        )

    elif cpu >= 90:

        score -= 12

        alerts.append(
            "🟠 CPU usage is very high."
        )


    if ram >= 95:

        score -= 20

        alerts.append(
            "🔴 Memory usage is critically high."
        )

    elif ram >= 90:

        score -= 12

        alerts.append(
            "🟠 Memory usage is very high."
        )


    if disk >= 95:

        score -= 25

        alerts.append(
            "🔴 C: drive is almost full."
        )

    elif disk >= 90:

        score -= 15

        alerts.append(
            "🟠 C: drive is getting full."
        )


    if gpu >= 98:

        score -= 5

        alerts.append(
            "🟠 GPU is under very high load."
        )


    if gpu_temp is not None:

        if gpu_temp >= 90:

            score -= 20

            alerts.append(
                "🔴 GPU temperature is critically high."
            )

        elif gpu_temp >= 85:

            score -= 10

            alerts.append(
                "🟠 GPU temperature is high."
            )


    if battery is not None and not charging:

        if battery <= 10:

            score -= 15

            alerts.append(
                "🔴 Battery level is critically low."
            )

        elif battery <= 20:

            score -= 8

            alerts.append(
                "🟠 Battery level is low."
            )


    score = max(
        0,
        min(100, score)
    )


    if score >= 90:

        status = "Excellent 🟢"
        color = GREEN

    elif score >= 75:

        status = "Good 🟢"
        color = GREEN

    elif score >= 55:

        status = "Needs Attention 🟠"
        color = ORANGE

    else:

        status = "Poor 🔴"
        color = RED


    return score, status, color, alerts


# ============================================================
# UPDATE HEALTH
# ============================================================

def update_health_ui():

    score, status, color, alerts = calculate_health()


    health_score.config(
        text=str(score),
        fg=color
    )


    health_status.config(
        text=status,
        fg=color
    )


    if alerts:

        alert_count.config(
            text=str(len(alerts)),
            fg=ORANGE
        )

        alert_box.config(
            text="\n".join(alerts),
            fg=ORANGE
        )

    else:

        alert_count.config(
            text="0",
            fg=GREEN
        )

        alert_box.config(
            text="✓ No problems detected",
            fg=GREEN
        )


    root.after(
        2000,
        update_health_ui
    )


# ============================================================
# GPU UI
# ============================================================

def update_gpu_ui():

    with gpu_lock:

        available = gpu_data["available"]

        name = gpu_data["name"]

        usage = gpu_data["usage"]

        temperature = gpu_data["temperature"]

        memory_used = gpu_data["memory_used"]

        memory_total = gpu_data["memory_total"]


    with data_lock:

        system_data["gpu"] = (
            usage
            if usage is not None
            else 0
        )

        system_data["gpu_temp"] = temperature


    if available:

        gpu_value.config(
            text=f"{usage:.0f}%",
            fg=usage_color(usage)
        )

        gpu_detail.config(
            text=(
                f"{name} • "
                f"{memory_used:.0f} / "
                f"{memory_total:.0f} MB VRAM"
            )
        )


        temperature_value.config(
            text=f"{temperature:.0f}°C",
            fg=(
                RED
                if temperature >= 90
                else ORANGE
                if temperature >= 85
                else GREEN
            )
        )

        temperature_detail.config(
            text=name
        )

    else:

        gpu_value.config(
            text="N/A",
            fg=TEXT
        )

        gpu_detail.config(
            text="NVIDIA GPU unavailable"
        )

        temperature_value.config(
            text="N/A",
            fg=TEXT
        )

        temperature_detail.config(
            text="Sensor unavailable"
        )


    root.after(
        1000,
        update_gpu_ui
    )


# ============================================================
# PROCESS UI
# ============================================================

def update_process_ui():

    with process_lock:

        data = list(process_data)


    for item in process_table.get_children():

        process_table.delete(item)


    for name, cpu, memory in data:

        process_table.insert(
            "",
            "end",
            values=(
                name,
                f"{cpu:.1f}%",
                f"{memory:.1f}%"
            )
        )


    root.after(
        1000,
        update_process_ui
    )


# ============================================================
# MAIN MONITOR
# ============================================================

def update_monitor():

    global previous_net
    global previous_time


    # CPU

    cpu = psutil.cpu_percent(
        interval=None
    )


    cpu_value.config(
        text=f"{cpu:.0f}%",
        fg=usage_color(cpu)
    )


    cpu_detail.config(
        text=(
            f"{physical_cpus} physical • "
            f"{logical_cpus} logical"
        )
    )


    # RAM

    memory = psutil.virtual_memory()


    ram_value.config(
        text=f"{memory.percent:.0f}%",
        fg=usage_color(
            memory.percent
        )
    )


    ram_detail.config(
        text=(
            f"{format_bytes(memory.used)} used • "
            f"{format_bytes(memory.available)} available"
        )
    )


    # Disk

    try:

        disk = psutil.disk_usage(
            "C:\\"
        )


        disk_value.config(
            text=f"{disk.percent:.0f}%",
            fg=usage_color(
                disk.percent
            )
        )


        disk_detail.config(
            text=(
                f"{format_bytes(disk.used)} used • "
                f"{format_bytes(disk.free)} free"
            )
        )

        disk_percent = disk.percent

    except Exception:

        disk_percent = 0

        disk_value.config(
            text="N/A"
        )

        disk_detail.config(
            text="Unable to read disk"
        )


    # Network

    current_net = psutil.net_io_counters()

    current_time = time.time()

    elapsed = (
        current_time -
        previous_time
    )


    if elapsed > 0:

        download_speed = (
            current_net.bytes_recv -
            previous_net.bytes_recv
        ) / elapsed

        upload_speed = (
            current_net.bytes_sent -
            previous_net.bytes_sent
        ) / elapsed

    else:

        download_speed = 0
        upload_speed = 0


    previous_net = current_net

    previous_time = current_time


    network_value.config(
        text=(
            f"↓ "
            f"{format_bytes(download_speed)}/s"
        )
    )


    network_detail.config(
        text=(
            f"↑ "
            f"{format_bytes(upload_speed)}/s"
        )
    )


    # Battery

    battery = psutil.sensors_battery()


    if battery:

        percent = battery.percent

        charging = battery.power_plugged


        battery_value.config(
            text=(
                f"{percent:.0f}% ⚡"
                if charging
                else f"{percent:.0f}%"
            ),
            fg=(
                GREEN
                if charging
                else usage_color(
                    100 - percent
                )
            )
        )


        battery_detail.config(
            text=(
                "Charging"
                if charging
                else "On battery"
            )
        )

        battery_percent = percent

    else:

        battery_percent = None

        charging = False

        battery_value.config(
            text="N/A",
            fg=TEXT
        )

        battery_detail.config(
            text="No battery detected"
        )


    # Uptime

    uptime = (
        time.time() -
        boot_time
    )


    uptime_value.config(
        text=format_uptime(
            uptime
        )
    )


    uptime_detail.config(
        text=(
            "Boot time "
            +
            time.strftime(
                "%H:%M:%S",
                time.localtime(
                    boot_time
                )
            )
        )
    )


    # Save current values

    with data_lock:

        system_data["cpu"] = cpu

        system_data["ram"] = memory.percent

        system_data["ram_used"] = memory.used

        system_data["ram_total"] = memory.total

        system_data["ram_available"] = memory.available

        system_data["disk"] = disk_percent

        if "disk" in locals():

            system_data["disk_used"] = disk.used

            system_data["disk_total"] = disk.total

            system_data["disk_free"] = disk.free

        system_data["battery"] = battery_percent

        system_data["battery_charging"] = charging

        system_data["download"] = download_speed

        system_data["upload"] = upload_speed


    root.after(
        1000,
        update_monitor
    )


# ============================================================
# HTML REPORT
# ============================================================

def generate_report():

    report_button.config(
        state="disabled",
        text="Generating..."
    )

    root.update_idletasks()


    try:

        with data_lock:

            cpu = system_data["cpu"]

            ram = system_data["ram"]

            ram_used = system_data["ram_used"]

            ram_total = system_data["ram_total"]

            ram_available = system_data["ram_available"]

            disk = system_data["disk"]

            disk_used = system_data["disk_used"]

            disk_total = system_data["disk_total"]

            disk_free = system_data["disk_free"]

            battery = system_data["battery"]

            charging = system_data["battery_charging"]

            download = system_data["download"]

            upload = system_data["upload"]


        with gpu_lock:

            gpu_available = gpu_data["available"]

            gpu_name = gpu_data["name"]

            gpu_usage = gpu_data["usage"]

            gpu_temp = gpu_data["temperature"]

            gpu_memory_used = gpu_data["memory_used"]

            gpu_memory_total = gpu_data["memory_total"]


        score, status, health_color, alerts = calculate_health()


        now = datetime.now()

        generated_time = now.strftime(
            "%d %B %Y, %I:%M:%S %p"
        )


        uptime = time.time() - boot_time


        # ----------------------------------------------------
        # STATUS TEXT
        # ----------------------------------------------------

        if score >= 90:

            health_text = "Excellent"

        elif score >= 75:

            health_text = "Good"

        elif score >= 55:

            health_text = "Needs Attention"

        else:

            health_text = "Poor"


        # ----------------------------------------------------
        # ALERT HTML
        # ----------------------------------------------------

        if alerts:

            alert_html = ""

            for alert in alerts:

                alert_html += f"""
                <div class="alert">
                    {html_escape(alert)}
                </div>
                """

        else:

            alert_html = """
            <div class="success">
                ✓ No problems detected
            </div>
            """


        # ----------------------------------------------------
        # GPU
        # ----------------------------------------------------

        if gpu_available:

            gpu_html = f"""
            <div class="metric">
                <span>GPU Model</span>
                <strong>{html_escape(gpu_name)}</strong>
            </div>

            <div class="metric">
                <span>GPU Usage</span>
                <strong>{gpu_usage:.0f}%</strong>
            </div>

            <div class="metric">
                <span>Temperature</span>
                <strong>{gpu_temp:.0f}°C</strong>
            </div>

            <div class="metric">
                <span>VRAM</span>
                <strong>
                    {gpu_memory_used:.0f} /
                    {gpu_memory_total:.0f} MB
                </strong>
            </div>
            """

        else:

            gpu_html = """
            <div class="success">
                NVIDIA GPU information unavailable.
            </div>
            """


        # ----------------------------------------------------
        # BATTERY
        # ----------------------------------------------------

        if battery is not None:

            battery_status = (
                "Charging"
                if charging
                else "Running on battery"
            )

            battery_html = f"""
            <div class="metric">
                <span>Battery Level</span>
                <strong>{battery:.0f}%</strong>
            </div>

            <div class="metric">
                <span>Status</span>
                <strong>{battery_status}</strong>
            </div>
            """

        else:

            battery_html = """
            <div class="success">
                No battery detected.
            </div>
            """


        # ----------------------------------------------------
        # RECOMMENDATIONS
        # ----------------------------------------------------

        recommendations = []


        if cpu >= 90:

            recommendations.append(
                "Check applications using unusually high CPU."
            )

        else:

            recommendations.append(
                "CPU usage is currently within a normal range."
            )


        if ram >= 90:

            recommendations.append(
                "Close unnecessary applications to free memory."
            )

        else:

            recommendations.append(
                "Memory usage is currently within a normal range."
            )


        if disk >= 90:

            recommendations.append(
                "Free up storage space on the C: drive."
            )

        else:

            recommendations.append(
                "Storage space is currently acceptable."
            )


        if gpu_temp is not None and gpu_temp >= 85:

            recommendations.append(
                "Check GPU cooling and airflow."
            )

        elif gpu_temp is not None:

            recommendations.append(
                "GPU temperature is currently acceptable."
            )


        if battery is not None and not charging and battery <= 20:

            recommendations.append(
                "Connect the charger because battery level is low."
            )


        recommendation_html = ""

        for recommendation in recommendations:

            recommendation_html += f"""
            <li>{html_escape(recommendation)}</li>
            """


        # ----------------------------------------------------
        # PROCESS TABLE
        # ----------------------------------------------------

        with process_lock:

            processes = list(process_data)


        process_html = ""


        for name, proc_cpu, proc_memory in processes:

            process_html += f"""
            <tr>
                <td>{html_escape(name)}</td>
                <td>{proc_cpu:.1f}%</td>
                <td>{proc_memory:.1f}%</td>
            </tr>
            """


        if not process_html:

            process_html = """
            <tr>
                <td colspan="3">
                    Process information unavailable
                </td>
            </tr>
            """


        # ----------------------------------------------------
        # HTML DOCUMENT
        # ----------------------------------------------------

        html = f"""<!DOCTYPE html>
<html lang="en">

<head>

<meta charset="UTF-8">

<meta name="viewport"
      content="width=device-width, initial-scale=1.0">

<title>SysPulse Health Report - {html_escape(hostname)}</title>

<style>

* {{
    box-sizing: border-box;
}}

body {{
    margin: 0;
    background: #f4f6f8;
    color: #1c1f26;
    font-family:
        "Segoe UI",
        Arial,
        sans-serif;
}}

.container {{
    max-width: 1050px;
    margin: auto;
    padding: 35px 25px 60px;
}}

.header {{
    background: #17191f;
    color: white;
    border-radius: 18px;
    padding: 30px;
    margin-bottom: 20px;
}}

.header h1 {{
    margin: 0 0 8px;
    font-size: 32px;
}}

.header p {{
    margin: 4px 0;
    color: #aeb3bd;
}}

.actions {{
    display: flex;
    gap: 12px;
    margin-bottom: 20px;
}}

.download {{
    border: none;
    background: #5b52e8;
    color: white;
    padding: 13px 20px;
    border-radius: 9px;
    font-size: 15px;
    font-weight: 700;
    cursor: pointer;
}}

.download:hover {{
    background: #4942ca;
}}

.score {{
    background: white;
    border-radius: 18px;
    padding: 25px;
    margin-bottom: 20px;
    display: flex;
    align-items: center;
    gap: 25px;
    box-shadow:
        0 3px 15px rgba(0,0,0,.06);
}}

.score-number {{
    font-size: 58px;
    font-weight: 800;
    color: {health_color};
}}

.score-text h2 {{
    margin: 0 0 5px;
}}

.score-text p {{
    margin: 0;
    color: #69707c;
}}

.section {{
    background: white;
    border-radius: 18px;
    padding: 25px;
    margin-bottom: 20px;
    box-shadow:
        0 3px 15px rgba(0,0,0,.06);
}}

.section h2 {{
    margin-top: 0;
    margin-bottom: 18px;
    font-size: 21px;
}}

.grid {{
    display: grid;
    grid-template-columns:
        repeat(2, minmax(0, 1fr));
    gap: 14px;
}}

.metric {{
    background: #f7f8fa;
    border-radius: 10px;
    padding: 15px;
    display: flex;
    justify-content: space-between;
    gap: 15px;
}}

.metric span {{
    color: #707783;
}}

.metric strong {{
    text-align: right;
}}

.alert {{
    background: #fff1e2;
    border-left: 5px solid #ff9f43;
    padding: 13px;
    margin-bottom: 10px;
    border-radius: 7px;
}}

.success {{
    background: #e9f9ef;
    border-left: 5px solid #32c76a;
    padding: 13px;
    border-radius: 7px;
}}

ul {{
    padding-left: 22px;
}}

li {{
    margin: 9px 0;
}}

table {{
    width: 100%;
    border-collapse: collapse;
}}

th,
td {{
    padding: 12px;
    text-align: left;
    border-bottom: 1px solid #e5e7eb;
}}

th {{
    background: #f4f5f7;
}}

.footer {{
    text-align: center;
    color: #8a909a;
    margin-top: 25px;
    font-size: 13px;
}}

@media(max-width: 700px) {{

    .grid {{
        grid-template-columns: 1fr;
    }}

    .score {{
        flex-direction: column;
        align-items: flex-start;
    }}

}}

@media print {{

    body {{
        background: white;
    }}

    .container {{
        max-width: none;
        padding: 0;
    }}

    .actions {{
        display: none;
    }}

    .section,
    .score,
    .header {{
        box-shadow: none;
        break-inside: avoid;
    }}

    .section {{
        page-break-inside: avoid;
    }}

}}

</style>

</head>

<body>

<div class="container">

    <div class="header">

        <h1>🩺 SysPulse Health Report</h1>

        <p>
            <strong>Computer:</strong>
            {html_escape(hostname)}
        </p>

        <p>
            <strong>Operating System:</strong>
            {html_escape(system_name)}
            {html_escape(system_version)}
        </p>

        <p>
            <strong>Generated:</strong>
            {html_escape(generated_time)}
        </p>

    </div>


    <div class="actions">

        <button
            class="download"
            onclick="window.print()">

            ⬇ Download SysPulse Report as PDF

        </button>

    </div>


    <div class="score">

        <div class="score-number">

            {score}

        </div>

        <div class="score-text">

            <h2>
                {health_text}
            </h2>

            <p>
                Overall system health based on
                current resource usage and detected
                conditions.
            </p>

        </div>

    </div>


    <div class="section">

        <h2>🖥️ CPU</h2>

        <div class="grid">

            <div class="metric">
                <span>Usage</span>
                <strong>{cpu:.0f}%</strong>
            </div>

            <div class="metric">
                <span>Physical Cores</span>
                <strong>{physical_cpus}</strong>
            </div>

            <div class="metric">
                <span>Logical Cores</span>
                <strong>{logical_cpus}</strong>
            </div>

            <div class="metric">
                <span>Processor</span>
                <strong>
                    {html_escape(processor_name or "Unknown")}
                </strong>
            </div>

        </div>

    </div>


    <div class="section">

        <h2>🧠 Memory</h2>

        <div class="grid">

            <div class="metric">
                <span>Usage</span>
                <strong>{ram:.0f}%</strong>
            </div>

            <div class="metric">
                <span>Total</span>
                <strong>{format_bytes(ram_total)}</strong>
            </div>

            <div class="metric">
                <span>Used</span>
                <strong>{format_bytes(ram_used)}</strong>
            </div>

            <div class="metric">
                <span>Available</span>
                <strong>{format_bytes(ram_available)}</strong>
            </div>

        </div>

    </div>


    <div class="section">

        <h2>🎮 GPU</h2>

        <div class="grid">

            {gpu_html}

        </div>

    </div>


    <div class="section">

        <h2>💾 Storage</h2>

        <div class="grid">

            <div class="metric">
                <span>Drive</span>
                <strong>C:</strong>
            </div>

            <div class="metric">
                <span>Usage</span>
                <strong>{disk:.0f}%</strong>
            </div>

            <div class="metric">
                <span>Total</span>
                <strong>{format_bytes(disk_total)}</strong>
            </div>

            <div class="metric">
                <span>Used</span>
                <strong>{format_bytes(disk_used)}</strong>
            </div>

            <div class="metric">
                <span>Free</span>
                <strong>{format_bytes(disk_free)}</strong>
            </div>

        </div>

    </div>


    <div class="section">

        <h2>🌐 Network</h2>

        <div class="grid">

            <div class="metric">
                <span>Download</span>
                <strong>
                    {format_bytes(download)}/s
                </strong>
            </div>

            <div class="metric">
                <span>Upload</span>
                <strong>
                    {format_bytes(upload)}/s
                </strong>
            </div>

        </div>

    </div>


    <div class="section">

        <h2>🔋 Battery</h2>

        <div class="grid">

            {battery_html}

        </div>

    </div>


    <div class="section">

        <h2>⏱️ System</h2>

        <div class="grid">

            <div class="metric">
                <span>Uptime</span>
                <strong>
                    {format_uptime(uptime)}
                </strong>
            </div>

            <div class="metric">
                <span>Boot Time</span>
                <strong>
                    {time.strftime(
                        "%d %b %Y %H:%M:%S",
                        time.localtime(boot_time)
                    )}
                </strong>
            </div>

        </div>

    </div>


    <div class="section">

        <h2>⚠️ Detected Alerts</h2>

        {alert_html}

    </div>


    <div class="section">

        <h2>💡 Recommendations</h2>

        <ul>

            {recommendation_html}

        </ul>

    </div>


    <div class="section">

        <h2>🔥 Top Processes</h2>

        <table>

            <thead>

                <tr>
                    <th>Process</th>
                    <th>CPU</th>
                    <th>Memory</th>
                </tr>

            </thead>

            <tbody>

                {process_html}

            </tbody>

        </table>

    </div>


    <div class="footer">

        SysPulse • Local system diagnostic report

    </div>

</div>

</body>

</html>
"""


        # ----------------------------------------------------
        # SAVE REPORT
        # ----------------------------------------------------

        if getattr(sys, "frozen", False):

            base_folder = os.path.dirname(
                sys.executable
            )

        else:

            base_folder = os.path.dirname(
                os.path.abspath(__file__)
            )


        report_folder = os.path.join(
            base_folder,
            "Reports"
        )


        os.makedirs(
            report_folder,
            exist_ok=True
        )


        filename = (
            "SysPulse_Health_Report_"
            +
            now.strftime(
                "%Y%m%d_%H%M%S"
            )
            +
            ".html"
        )


        report_path = os.path.join(
            report_folder,
            filename
        )


        with open(
            report_path,
            "w",
            encoding="utf-8"
        ) as file:

            file.write(html)


        # ----------------------------------------------------
        # OPEN BROWSER
        # ----------------------------------------------------

        webbrowser.open(
            "file:///"
            +
            os.path.abspath(
                report_path
            ).replace(
                "\\",
                "/"
            )
        )


    except Exception as error:

        print(
            "Report generation error:",
            error
        )

    finally:

        report_button.config(
            state="normal",
            text="🩺  Full SysPulse Health Report"
        )


# ============================================================
# START
# ============================================================

update_monitor()

update_gpu_ui()

update_process_ui()

update_health_ui()

root.mainloop()